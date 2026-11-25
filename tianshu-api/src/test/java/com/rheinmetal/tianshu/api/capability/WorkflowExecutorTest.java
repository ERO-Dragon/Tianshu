package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.CancellationSignal;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workflow executor: it runs steps as the caller, stops where it must, and says where it stopped.
 *
 * <p>These are the four rules the channel exists for. They are asserted here rather than in a game test because a plan is
 * data and its execution does not need a world — the game-side evidence belongs to the reference workflow in
 * {@code tianshu-lab}.</p>
 */
class WorkflowExecutorTest {

    private static final ToolCaller CALLER = ToolCaller.agent("tianshu", false, ToolAutonomy.CONSERVATIVE);

    @AfterEach
    void cleanUp() {
        CapabilityCatalog.unregister(CapabilityId.parse("test:first"));
        CapabilityCatalog.unregister(CapabilityId.parse("test:second"));
        CapabilityCatalog.unregister(CapabilityId.parse("test:refuses"));
    }

    @Test
    void runsEveryStepInOrderAsTheCaller() {
        AtomicInteger calls = new AtomicInteger();
        register("test:first", "provider-a", (context, args) -> {
            calls.incrementAndGet();
            assertEquals(CALLER, context.caller(), "steps run as the caller, never as the provider");
            return ok();
        });
        register("test:second", "provider-b", (context, args) -> {
            calls.incrementAndGet();
            return ok();
        });

        WorkflowRun run = run(plan(CapabilityId.parse("test:first"), CapabilityId.parse("test:second")),
                CancellationSignal.NEVER);

        assertTrue(run.completed());
        assertEquals(2, run.executedSteps());
        assertEquals(2, run.totalSteps());
        assertEquals(2, calls.get());
    }

    @Test
    void stopsAtAFailedStepAndNamesItWithItsProvider() {
        register("test:first", "provider-a", (context, args) -> ok());
        register("test:refuses", "provider-b", (context, args) -> {
            JsonObject refusal = new JsonObject();
            refusal.addProperty("ok", false);
            refusal.addProperty("code", "NO_PERMISSION");
            refusal.addProperty("error", "not allowed");
            return CompletableFuture.completedFuture(refusal);
        });
        AtomicInteger afterFailure = new AtomicInteger();
        register("test:second", "provider-c", (context, args) -> {
            afterFailure.incrementAndGet();
            return ok();
        });

        WorkflowRun run = run(plan(CapabilityId.parse("test:first"), CapabilityId.parse("test:refuses"),
                CapabilityId.parse("test:second")), CancellationSignal.NEVER);

        assertFalse(run.completed());
        assertEquals(1, run.failedStep(), "the failing step is the second one, zero-indexed");
        assertEquals(1, run.executedSteps());
        assertTrue(run.failure().contains("test:refuses"), run.failure());
        assertTrue(run.failure().contains("provider-b"), "a failure has to name who provides the step: " + run.failure());
        assertTrue(run.failure().contains("NO_PERMISSION"), run.failure());
        assertEquals(0, afterFailure.get(), "the steps after a failure were written assuming it worked");
    }

    @Test
    void stopsBetweenStepsWhenCancelled() {
        AtomicInteger calls = new AtomicInteger();
        register("test:first", "provider-a", (context, args) -> {
            calls.incrementAndGet();
            return ok();
        });
        register("test:second", "provider-b", (context, args) -> {
            calls.incrementAndGet();
            return ok();
        });

        // Cancelled once the first step has run: the second must not start.
        CancellationSignal afterFirst = () -> calls.get() >= 1;
        WorkflowRun run = run(plan(CapabilityId.parse("test:first"), CapabilityId.parse("test:second")), afterFirst);

        assertFalse(run.completed());
        assertTrue(run.cancelled());
        assertEquals(1, run.executedSteps());
        assertEquals(1, calls.get());
    }

    @Test
    void anUnknownStepCapabilityIsAFailureNotASilentSkip() {
        register("test:first", "provider-a", (context, args) -> ok());

        WorkflowRun run = run(plan(CapabilityId.parse("test:first"), CapabilityId.parse("test:missing")),
                CancellationSignal.NEVER);

        assertFalse(run.completed());
        assertEquals(1, run.failedStep());
        assertTrue(run.failure().contains("test:missing"), run.failure());
    }

    @Test
    void aPlanNeedsStepsAndADescription() {
        CapabilityId id = CapabilityId.parse("test:plan");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDeclaration(id, "  ", List.of(WorkflowStep.of("test:first")), 1, false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkflowDeclaration(id, "a plan", List.of(), 1, false));
    }

    private static WorkflowDeclaration plan(CapabilityId... steps) {
        return new WorkflowDeclaration(CapabilityId.parse("test:plan"), "a test workflow",
                java.util.Arrays.stream(steps).map(WorkflowStep::of).toList(), 5, false);
    }

    private static WorkflowRun run(WorkflowDeclaration plan, CancellationSignal cancellation) {
        return WorkflowExecutor.run(plan, CALLER, ToolSide.SERVER, cancellation).join();
    }

    private static CompletableFuture<JsonObject> ok() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        return CompletableFuture.completedFuture(envelope);
    }

    private static void register(String id, String provider, CapabilityHandler handler) {
        CapabilityId capability = CapabilityId.parse(id);
        CapabilityCatalog.register(new CapabilityDescriptor(capability, CapabilityKind.of("test"),
                CapabilityBinding.LOCAL, "a test step", ToolSide.SERVER, ToolPermission.EVERYONE, provider,
                new JsonObject()), handler);
    }
}