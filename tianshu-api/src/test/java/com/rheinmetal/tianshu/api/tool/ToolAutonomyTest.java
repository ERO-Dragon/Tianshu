package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only hides writes; confirm asks exactly once; full runs without a prompt; server dispatch never asks. */
class ToolAutonomyTest {

    private static final ToolId WRITE = ToolId.of("test", "autonomy.write");

    @BeforeEach
    void setUp() {
        ToolAutonomyGate.reset();
        TianshuTools.clearRateLimits();
        TianshuTools.register(new WriteTool());
        TianshuTools.setEnabled(WRITE, true);
        TianshuTools.installServerChannel(new ServerToolChannel() {
            @Override
            public CompletableFuture<com.google.gson.JsonElement> call(ToolId id, com.google.gson.JsonElement args,
                                                                       ToolCaller caller, long timeoutMillis) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        });
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(WRITE);
        TianshuTools.uninstallServerChannel();
        ToolAutonomyGate.reset();
    }

    @Test
    void readOnlyHidesAndRefusesWriteTools() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.READ_ONLY);

        assertFalse(TianshuTools.catalog(ToolCaller.agent(true), ToolSide.CLIENT).stream()
                .anyMatch(spec -> spec.id().equals(WRITE)));
        JsonObject denied = callClient();
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void confirmWithoutAConfirmerIsDenied() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.CONFIRM);

        JsonObject denied = callClient();
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void confirmAsksThePlayerAndObeysTheAnswer() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.CONFIRM);
        ToolAutonomyGate.installConfirmation(attempt -> CompletableFuture.completedFuture(true));
        assertTrue(callClient().get("ok").getAsBoolean());

        ToolAutonomyGate.installConfirmation(attempt -> CompletableFuture.completedFuture(false));
        assertEquals("NO_PERMISSION", callClient().get("code").getAsString());
    }

    @Test
    void fullRunsWithoutAPromptButStillNeedsAnOperator() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.FULL);

        assertTrue(callClient().get("ok").getAsBoolean());

        JsonObject denied = TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.agent(false), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void serverDispatchIsNotGatedAgain() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.CONFIRM);

        JsonObject result = TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.player("p", true),
                        ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean(), "the client already approved; the server trusts operator+switch");
    }

    private static JsonObject callClient() throws Exception {
        return TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }

    public static final class Args {
    }

    public static final class Reply {
    }

    private static final class WriteTool implements TianshuTool<Args, Reply> {
        @Override
        public ToolId id() {
            return WRITE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_WRITE;
        }

        @Override
        public String description() {
            return "Write; an autonomy test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            return CompletableFuture.completedFuture(new Reply());
        }
    }
}
