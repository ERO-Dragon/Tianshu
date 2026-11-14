package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.ServerToolChannel;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCallHandle;
import com.rheinmetal.tianshu.api.tool.ReservedToolCalls;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionToolChannelTest {

    private static final ToolId REMOTE = ToolId.of("test", "remote");
    private static final ToolId OPERATOR_ONLY = ToolId.of("test", "operator-remote");
    private static final ToolId DECLARED = ToolId.of("test", "declared");

    private final AtomicReference<String> lastRequestId = new AtomicReference<>();
    private final AtomicReference<JsonElement> lastArgs = new AtomicReference<>();
    private final AtomicReference<ToolId> lastToolId = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        TianshuTools.register(new RemoteTool(REMOTE, ToolCategory.SERVER_READ,
                ToolPolicy.forCategory(ToolCategory.SERVER_READ)));
        TianshuTools.register(new RemoteTool(OPERATOR_ONLY, ToolCategory.SERVER_READ,
                ToolPolicy.forCategory(ToolCategory.SERVER_READ)));
        TianshuRuntimePorts.register(ToolTransport.class, (requestId, id, args, caller, timeoutMillis) -> {
            lastRequestId.set(requestId);
            lastToolId.set(id);
            lastArgs.set(args);
        });
        ConnectionToolChannel.install();
    }

    @AfterEach
    void clean() {
        ConnectionToolChannel.uninstall();
        TianshuTools.unregister(REMOTE);
        TianshuTools.unregister(OPERATOR_ONLY);
        ServerToolDeclarations.clearForTests();
        TianshuRuntimePorts.clear();
        TianshuTools.uninstallServerChannel();
    }

    @Test
    void aCallTravelsOutAndCompletesWhenTheAnswerArrives() throws Exception {
        CompletableFuture<JsonObject> envelope = TianshuTools.callJson(REMOTE,
                JsonParser.parseString("{\"text\":\"hello\"}"), ToolCaller.agent(false), ToolSide.CLIENT);
        assertFalse(envelope.isDone(), "the call must wait for the server");

        ConnectionToolChannel.complete(lastRequestId.get(), JsonParser.parseString("{\"reply\":\"world\"}"));

        JsonObject result = envelope.get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("world", result.getAsJsonObject("result").get("reply").getAsString());
        assertEquals("hello", lastArgs.get().getAsJsonObject().get("text").getAsString());
    }

    @Test
    void oneCallerCannotFillTheWholePendingWindow() throws Exception {
        for (int index = 0; index < 8; index++) {
            TianshuTools.callJson(REMOTE, JsonParser.parseString("{}"), ToolCaller.agent(false));
        }
        JsonObject ninth = TianshuTools.callJson(REMOTE, JsonParser.parseString("{}"),
                ToolCaller.agent(false)).get(2, TimeUnit.SECONDS);

        assertFalse(ninth.get("ok").getAsBoolean());
        assertEquals("BUSY", ninth.get("code").getAsString());
    }

    @Test
    void aCatalogRefreshActivatesTheDeclarationsTheServerNames() throws Exception {
        ServerToolDeclarations.declare(new RemoteTool(DECLARED, ToolCategory.SERVER_READ,
                ToolPolicy.forCategory(ToolCategory.SERVER_READ)));

        CompletableFuture<Void> refresh = ConnectionToolChannel.refreshCatalog();
        assertEquals(ReservedToolCalls.CATALOG, lastToolId.get());
        ConnectionToolChannel.complete(lastRequestId.get(),
                JsonParser.parseString("{\"tools\":[\"test:declared\"]}"));

        refresh.get(2, TimeUnit.SECONDS);
        assertEquals(DECLARED, TianshuTools.find(DECLARED).id());
    }

    @Test
    void aHandledCallCanBeCancelledAndTheServerIsNotified() throws Exception {
        ToolCallHandle handle = TianshuTools.callHandled(REMOTE, JsonParser.parseString("{}"),
                ToolCaller.agent(false), ToolSide.CLIENT);
        assertFalse(handle.result().isDone());
        assertTrue(handle.cancel());

        JsonObject envelope = handle.result().get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("CANCELLED", envelope.get("code").getAsString());
        assertEquals(ToolId.of("tianshu", "tool.cancel"), lastToolId.get(),
                "the server must be told about the cancel");
    }

    @Test
    void aRefusedCallReportsItsCode() throws Exception {
        CompletableFuture<JsonObject> envelope = TianshuTools.callJson(REMOTE, JsonParser.parseString("{}"),
                ToolCaller.agent(false));
        ConnectionToolChannel.fail(lastRequestId.get(), ToolError.NO_PERMISSION, "not an operator");

        JsonObject result = envelope.get(2, TimeUnit.SECONDS);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", result.get("code").getAsString());
    }

    @Test
    void anUnansweredCallTimesOut() throws Exception {
        TianshuTools.register(new RemoteTool(ToolId.of("test", "slow-remote"), ToolCategory.SERVER_READ,
                ToolPolicy.forCategory(ToolCategory.SERVER_READ).withTimeout(100)));
        try {
            JsonObject result = TianshuTools.callJson(ToolId.of("test", "slow-remote"),
                            JsonParser.parseString("{}"), ToolCaller.agent(false))
                    .get(2, TimeUnit.SECONDS);
            assertFalse(result.get("ok").getAsBoolean());
            assertEquals("TIMEOUT", result.get("code").getAsString());
        } finally {
            TianshuTools.unregister(ToolId.of("test", "slow-remote"));
        }
    }

    @Test
    void withoutATransportServerToolsAreInvisibleAndUnavailable() throws Exception {
        TianshuRuntimePorts.clear();

        assertTrue(TianshuTools.catalog(ToolCaller.agent(false)).stream()
                .noneMatch(spec -> spec.id().equals(REMOTE)));

        JsonObject result = TianshuTools.callJson(REMOTE, JsonParser.parseString("{}"), ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_SIDE", result.get("code").getAsString());
    }

    @Test
    void aLateAnswerForAnUnknownRequestIsIgnored() {
        ConnectionToolChannel.complete("tool-unknown", JsonParser.parseString("{}"));
        ConnectionToolChannel.fail("tool-unknown", ToolError.FAILED, "late");
    }

    // ---------------------------------------------------------------- fixtures

    public static final class Args {
        public String text;
    }

    public static final class Answer {
        public String reply;
    }

    private static final class RemoteTool implements TianshuTool<Args, Answer> {
        private final ToolId id;
        private final ToolCategory category;
        private final ToolPolicy policy;

        RemoteTool(ToolId id, ToolCategory category, ToolPolicy policy) {
            this.id = id;
            this.category = category;
            this.policy = policy;
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public ToolCategory category() {
            return category;
        }

        @Override
        public String description() {
            return "Runs on the server; a test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Answer.class;
        }

        @Override
        public ToolPolicy policy() {
            return policy;
        }

        @Override
        public CompletableFuture<Answer> invoke(ToolContext context, Args args) {
            return CompletableFuture.completedFuture(new Answer());
        }
    }
}