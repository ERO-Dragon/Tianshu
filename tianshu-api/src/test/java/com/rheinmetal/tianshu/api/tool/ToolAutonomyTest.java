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

/** Risk tiers: read-only hides writes, conservative allows survival writes, full adds the privileged end. */
class ToolAutonomyTest {

    private static final ToolId WRITE = ToolId.of("test", "autonomy.write");
    private static final ToolId FULL_ONLY = ToolId.of("test", "autonomy.full");

    @BeforeEach
    void setUp() {
        ToolAutonomyGate.reset();
        TianshuTools.clearRateLimits();
        TianshuTools.register(new WriteTool());
        TianshuTools.register(new FullOnlyTool());
        TianshuTools.setEnabled(WRITE, true);
        TianshuTools.setEnabled(FULL_ONLY, true);
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
        TianshuTools.unregister(FULL_ONLY);
        TianshuTools.uninstallServerChannel();
        ToolAutonomyGate.reset();
    }

    @Test
    void readOnlyHidesAndRefusesEveryWrite() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.READ_ONLY);

        assertFalse(visible(WRITE));
        assertFalse(visible(FULL_ONLY));
        assertEquals("NO_PERMISSION", callClient(WRITE).get("code").getAsString());
        assertEquals("NO_PERMISSION", callClient(FULL_ONLY).get("code").getAsString());
    }

    @Test
    void conservativeAllowsSurvivalWritesButNotThePrivilegedEnd() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.CONSERVATIVE);

        assertTrue(visible(WRITE));
        assertFalse(visible(FULL_ONLY));
        assertTrue(callClient(WRITE).get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", callClient(FULL_ONLY).get("code").getAsString());
    }

    @Test
    void fullAllowsEveryTier() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.FULL);

        assertTrue(visible(WRITE));
        assertTrue(visible(FULL_ONLY));
        assertTrue(callClient(WRITE).get("ok").getAsBoolean());
        assertTrue(callClient(FULL_ONLY).get("ok").getAsBoolean());
    }

    @Test
    void theGateStillNeedsAnOperator() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.FULL);

        JsonObject denied = TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.agent(false), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    @Test
    void serverDispatchAppliesTheSameTier() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.CONSERVATIVE);

        assertTrue(TianshuTools.callJson(WRITE, new JsonObject(), ToolCaller.player("p", true), ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS).get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION",
                TianshuTools.callJson(FULL_ONLY, new JsonObject(), ToolCaller.player("p", true), ToolSide.SERVER)
                        .get(2, TimeUnit.SECONDS).get("code").getAsString());
    }

    private static boolean visible(ToolId id) {
        return TianshuTools.catalog(ToolCaller.agent(true), ToolSide.CLIENT).stream()
                .anyMatch(spec -> spec.id().equals(id));
    }

    private static JsonObject callClient(ToolId id) throws Exception {
        return TianshuTools.callJson(id, new JsonObject(), ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }

    public static final class Args {
    }

    public static final class Reply {
    }

    private static class WriteTool implements TianshuTool<Args, Reply> {
        private final ToolId id;
        private final ToolAutonomy autonomy;

        WriteTool() {
            this(WRITE, ToolAutonomy.CONSERVATIVE);
        }

        WriteTool(ToolId id, ToolAutonomy autonomy) {
            this.id = id;
            this.autonomy = autonomy;
        }

        @Override
        public ToolId id() {
            return id;
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
        public ToolAutonomy requiredAutonomy() {
            return autonomy;
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

    private static final class FullOnlyTool extends WriteTool {
        FullOnlyTool() {
            super(FULL_ONLY, ToolAutonomy.FULL);
        }
    }
}