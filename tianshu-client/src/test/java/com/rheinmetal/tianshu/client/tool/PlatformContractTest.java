package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.ServerToolDispatch;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCallHandle;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end through the real channel and the real dispatcher, with only the network replaced. */
class PlatformContractTest {

    private static final ToolId ECHO = ToolId.of("test", "contract.echo");
    private static final ToolId BLOCK = ToolId.of("test", "contract.block");

    @BeforeEach
    void setUp() {
        TianshuTools.register(new ContractTool(ECHO, CompletableFuture.completedFuture(payload("pong"))));
        TianshuTools.register(new ContractTool(BLOCK, new CompletableFuture<>()));
        TianshuRuntimePorts.register(ToolTransport.class, (requestId, id, args, caller, timeoutMillis) ->
                ServerToolDispatch.respond(caller, requestId, id.toString(), args == null ? "{}" : args.toString())
                        .whenComplete((reply, failure) -> {
                            if (failure != null) {
                                ConnectionToolChannel.fail(requestId, ToolError.FAILED, failure.getMessage());
                            } else if (reply.ok()) {
                                ConnectionToolChannel.complete(requestId, JsonParser.parseString(reply.valueJson()));
                            } else {
                                ConnectionToolChannel.fail(requestId, code(reply.errorCode()), reply.message());
                            }
                        }));
        ConnectionToolChannel.install();
    }

    @AfterEach
    void clean() {
        ConnectionToolChannel.uninstall();
        TianshuTools.unregister(ECHO);
        TianshuTools.unregister(BLOCK);
        ServerToolDispatch.clear();
        TianshuRuntimePorts.clear();
    }

    @Test
    void aCallTraversesChannelAndDispatchWithMetadata() throws Exception {
        JsonObject envelope = TianshuTools.callJson(ECHO, new JsonObject(), ToolCaller.agent(false))
                .get(2, TimeUnit.SECONDS);

        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals("pong", envelope.getAsJsonObject("result").get("value").getAsString());
        assertTrue(envelope.get("durationMs").getAsLong() >= 0);
        assertTrue(envelope.get("bytes").getAsInt() > 0);
    }

    @Test
    void cancellingAHandledCallStopsBothSides() throws Exception {
        ToolCallHandle handle = TianshuTools.callHandled(BLOCK, new JsonObject(), ToolCaller.agent(false),
                com.rheinmetal.tianshu.api.tool.ToolSide.CLIENT);
        assertFalse(handle.result().isDone());
        assertTrue(handle.cancel());

        JsonObject envelope = handle.result().get(2, TimeUnit.SECONDS);
        assertFalse(envelope.get("ok").getAsBoolean());
        assertEquals("CANCELLED", envelope.get("code").getAsString());
    }

    private static JsonObject payload(String value) {
        JsonObject object = new JsonObject();
        object.addProperty("value", value);
        return object;
    }

    private static ToolError code(String raw) {
        try {
            return ToolError.valueOf(raw);
        } catch (RuntimeException ignored) {
            return ToolError.FAILED;
        }
    }

    private record ContractTool(ToolId id, CompletableFuture<JsonObject> result)
            implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_READ;
        }

        @Override
        public String description() {
            return "Contract test tool.";
        }

        @Override
        public Type argsType() {
            return JsonObject.class;
        }

        @Override
        public Type resultType() {
            return JsonObject.class;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return result;
        }
    }
}
