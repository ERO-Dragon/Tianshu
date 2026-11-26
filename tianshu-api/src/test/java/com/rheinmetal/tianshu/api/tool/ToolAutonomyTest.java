package com.rheinmetal.tianshu.api.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Risk tiers: read-only hides writes, conservative allows survival writes, full adds the privileged end.
 *
 * <p>The tier belongs to the caller, so every case here builds the caller it means to test instead of mutating a
 * process-wide setting. The last two cases cover the server's side of the same question, which the client's tier is
 * not allowed to widen.</p>
 */
class ToolAutonomyTest {

    @BeforeEach
    void subscribeToFixtures() {
        CapabilitySubscriptions.declare(MODULE, CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
        CapabilitySubscriptions.declare("module.bold", CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
        CapabilitySubscriptions.declare("module.cautious", CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
    }

    @AfterEach
    void withdrawSubscriptions() {
        CapabilitySubscriptions.withdraw(MODULE);
        CapabilitySubscriptions.withdraw("module.bold");
        CapabilitySubscriptions.withdraw("module.cautious");
    }


    private static final ToolId WRITE = ToolId.of("test", "autonomy.write");
    private static final ToolId FULL_ONLY = ToolId.of("test", "autonomy.full");
    private static final String MODULE = "module.test";

    @BeforeEach
    void setUp() {
        TianshuTools.clearRateLimits();
        TianshuTools.registerBuiltin(new WriteTool());
        TianshuTools.registerBuiltin(new FullOnlyTool());
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
        TianshuRuntimePorts.clear();
    }

    @Test
    void aReadOnlyCallerHidesAndRefusesEveryWrite() throws Exception {
        ToolCaller caller = ToolCaller.agent(MODULE, true, ToolAutonomy.READ_ONLY);

        assertFalse(visible(WRITE, caller));
        assertFalse(visible(FULL_ONLY, caller));
        assertEquals("NO_PERMISSION", callClient(WRITE, caller).get("code").getAsString());
        assertEquals("NO_PERMISSION", callClient(FULL_ONLY, caller).get("code").getAsString());
    }

    @Test
    void aConservativeCallerAllowsSurvivalWritesButNotThePrivilegedEnd() throws Exception {
        ToolCaller caller = ToolCaller.agent(MODULE, true, ToolAutonomy.CONSERVATIVE);

        assertTrue(visible(WRITE, caller));
        assertFalse(visible(FULL_ONLY, caller));
        assertTrue(callClient(WRITE, caller).get("ok").getAsBoolean());

        JsonObject refused = callClient(FULL_ONLY, caller);
        assertEquals("NO_PERMISSION", refused.get("code").getAsString());
        assertTrue(refused.get("message").getAsString().contains("CONSERVATIVE"),
                "the refusal has to name the tier that was applied: " + refused);
    }

    @Test
    void aFullCallerAllowsEveryTier() throws Exception {
        ToolCaller caller = ToolCaller.agent(MODULE, true, ToolAutonomy.FULL);

        assertTrue(visible(WRITE, caller));
        assertTrue(visible(FULL_ONLY, caller));
        assertTrue(callClient(WRITE, caller).get("ok").getAsBoolean());
        assertTrue(callClient(FULL_ONLY, caller).get("ok").getAsBoolean());
    }

    /**
     * The reason the tier moved onto the caller: two AI modules in one process have to be able to disagree, and a
     * process-wide setting can only ever hold one of the two answers.
     */
    @Test
    void twoModulesInOneProcessKeepTheirOwnTiers() throws Exception {
        ToolCaller cautious = ToolCaller.agent("module.cautious", true, ToolAutonomy.READ_ONLY);
        ToolCaller bold = ToolCaller.agent("module.bold", true, ToolAutonomy.FULL);

        assertFalse(visible(FULL_ONLY, cautious));
        assertTrue(visible(FULL_ONLY, bold));
        assertEquals("NO_PERMISSION", callClient(FULL_ONLY, cautious).get("code").getAsString());
        assertTrue(callClient(FULL_ONLY, bold).get("ok").getAsBoolean());
    }

    @Test
    void anOperatorIsStillNeededAtTheTopTier() throws Exception {
        ToolCaller notOperator = ToolCaller.agent(MODULE, false, ToolAutonomy.FULL);

        JsonObject denied = TianshuTools.callJson(WRITE, new JsonObject(), notOperator, ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);

        assertEquals("NO_PERMISSION", denied.get("code").getAsString());
    }

    /**
     * Server-side execution is the server's decision: its ceiling applies even to a caller that claims the top tier,
     * because the server cannot verify that claim and must not widen on it.
     */
    @Test
    void theServerCeilingBoundsEvenAFullCaller() throws Exception {
        TianshuRuntimePorts.register(ServerAutonomyCeiling.class, () -> ToolAutonomy.CONSERVATIVE);
        ToolCaller full = ToolCaller.agent(MODULE, true, ToolAutonomy.FULL);

        assertTrue(TianshuTools.callJson(WRITE, new JsonObject(), full, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS).get("ok").getAsBoolean());

        JsonObject refused = TianshuTools.callJson(FULL_ONLY, new JsonObject(), full, ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertEquals("NO_PERMISSION", refused.get("code").getAsString());
        assertTrue(refused.get("message").getAsString().contains("this server allows CONSERVATIVE"),
                "the refusal must name the ceiling so an operator can find the switch: " + refused);

        assertFalse(TianshuTools.catalog(full, ToolSide.SERVER).stream()
                        .anyMatch(spec -> spec.id().equals(FULL_ONLY)),
                "a ceiling the server enforces must also keep the tool out of the catalogue it pushes");
    }

    @Test
    void noPublishedCeilingMeansNoExtraRestriction() throws Exception {
        ToolCaller full = ToolCaller.agent(MODULE, true, ToolAutonomy.FULL);

        assertTrue(TianshuTools.callJson(FULL_ONLY, new JsonObject(), full, ToolSide.SERVER)
                        .get(2, TimeUnit.SECONDS).get("ok").getAsBoolean(),
                "a single-player world publishes no ceiling and must not restrict its own owner");
    }

    private static boolean visible(ToolId id, ToolCaller caller) {
        return TianshuTools.catalog(caller, ToolSide.CLIENT).stream()
                .anyMatch(spec -> spec.id().equals(id));
    }

    private static JsonObject callClient(ToolId id, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(id, new JsonObject(), caller, ToolSide.CLIENT).get(2, TimeUnit.SECONDS);
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