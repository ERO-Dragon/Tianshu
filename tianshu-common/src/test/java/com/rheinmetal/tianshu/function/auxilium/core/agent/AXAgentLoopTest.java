package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXAgentLoopTest {

    @Test
    void simpleRunCompletesInOneStepWithoutTools() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.simple(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        AXRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            assertEquals(0, context.stepIndex(), "主对话第一步就是唯一一步");
            assertEquals(AXRunPolicy.SIMPLE_TIMEOUT_MILLIS, context.remainingMillis());
            assertFalse(context.policy().toolsAllowed(), "主对话不带 tools[]（设计 §4.2）");
            assertEquals(CancellationScope.SELF_ONLY, context.policy().cancellationScope());
            return new AXStepOutcome.Finished<String>("暮色森林从要塞旁的传送门进");
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.COMPLETED, outcome.termination());
        assertEquals(1, outcome.steps());
        assertEquals("暮色森林从要塞旁的传送门进", outcome.result());
        assertNull(outcome.cancellation());
        assertNull(outcome.failure());
        assertEquals(1, calls.get());
    }

    @Test
    void repeatedContinuationHitsSimpleStepValveAndDegrades() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.simple(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        AXRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            return new AXStepOutcome.Continue<String>("prompt.reask", "text=" + context.stepIndex());
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.ABORT_MAX_STEPS, outcome.termination());
        assertEquals(AXRunPolicy.SIMPLE_MAX_STEPS, outcome.steps());
        assertEquals(3, calls.get(), "安全阀在请求之前判定，撞阀后不再多发一次请求");
        assertNull(outcome.result(), "超步数返回空结果，调用方走设计 §4.1 的降级路径");
    }

    @Test
    void simpleRunAbortsWhenWallClockPassesDeadline() {
        AtomicLong clockNanos = new AtomicLong();
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.simple(), clockNanos::get);

        AXRunOutcome<String> outcome = loop.run(context -> {
            clockNanos.addAndGet(AXRunPolicy.SIMPLE_TIMEOUT_MILLIS * 1_000_000L);
            return new AXStepOutcome.Continue<String>("wiki.search", "q=" + context.stepIndex());
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.ABORT_TIMEOUT, outcome.termination());
        assertEquals(1, outcome.steps());
        assertEquals(AXRunPolicy.SIMPLE_TIMEOUT_MILLIS, outcome.elapsedMillis());
        assertNull(outcome.result());
    }

    @Test
    void cancellationBeforeTheFirstStepReturnsTheReleaseReason() {
        AXTurnCancellation cancellation = AXTurnCancellation.playerInterrupted("玩家又说话了");
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.complex(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        AXRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            return new AXStepOutcome.Finished<String>("不该走到这里");
        }, AXRunCancellation.of(() -> true, () -> cancellation));

        assertEquals(AXRunTermination.CANCELLED, outcome.termination());
        assertEquals(0, outcome.steps());
        assertEquals(0, calls.get(), "取消后一步都不许发");
        assertSame(cancellation, outcome.cancellation());
        assertNull(outcome.result());
    }

    @Test
    void complexRunAbortsAfterThreeIdenticalSteps() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.complex(), () -> 0L);

        AXRunOutcome<String> outcome = loop.run(
                context -> new AXStepOutcome.Continue<String>("wiki.search", "{\"q\":\"twilight forest portal\"}"),
                AXRunCancellation.none());

        assertEquals(AXRunTermination.ABORT_NO_PROGRESS, outcome.termination());
        assertEquals(AXNoProgressDetector.STREAK_LIMIT, outcome.steps());
        assertEquals(3, outcome.steps());
        assertNull(outcome.result());
    }

    @Test
    void complexRunToleratesMoreStepsThanSimpleBeforeConverging() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.complex(), () -> 0L);

        AXRunOutcome<String> outcome = loop.run(context -> {
            if (context.stepIndex() < 5) {
                return new AXStepOutcome.Continue<String>("recipe.lookup", "id=" + context.stepIndex());
            }
            return new AXStepOutcome.Finished<String>("四个圆石摆成 2x2 得到石砖");
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.COMPLETED, outcome.termination());
        assertEquals(6, outcome.steps(), "Complex 的阀是 25 步，Simple 的 3 步不该套用到这里");
        assertEquals("四个圆石摆成 2x2 得到石砖", outcome.result());
    }

    @Test
    void stepFailureSurfacesAsFailedRun() {
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.complex(), () -> 0L);
        IllegalStateException failure = new IllegalStateException("wiki 网关超时");

        AXRunOutcome<String> outcome = loop.run(context -> {
            throw failure;
        }, AXRunCancellation.none());

        assertEquals(AXRunTermination.FAILED, outcome.termination());
        assertEquals(0, outcome.steps());
        assertSame(failure, outcome.failure());
        assertNull(outcome.result());
    }

    @Test
    void presetsCarryTheDesignNumbers() {
        AXRunPolicy simple = AXRunPolicy.simple();
        assertEquals(AXRunPolicy.SIMPLE_ID, simple.id());
        assertEquals(3, simple.maxSteps());
        assertEquals(10_000L, simple.timeoutMillis());
        assertFalse(simple.toolsAllowed());
        assertFalse(simple.noProgressDetection());
        assertEquals(CancellationScope.SELF_ONLY, simple.cancellationScope());

        AXRunPolicy complex = AXRunPolicy.complex();
        assertEquals(AXRunPolicy.COMPLEX_ID, complex.id());
        assertEquals(25, complex.maxSteps());
        assertEquals(120_000L, complex.timeoutMillis());
        assertTrue(complex.toolsAllowed());
        assertTrue(complex.noProgressDetection());
        assertEquals(CancellationScope.CHILDREN, complex.cancellationScope());
    }

    @Test
    void degeneratePolicyAndOutcomeAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunPolicy(" ", 3, 10_000L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunPolicy(AXRunPolicy.SIMPLE_ID, 0, 10_000L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunPolicy(AXRunPolicy.SIMPLE_ID, 3, 0L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunPolicy(AXRunPolicy.SIMPLE_ID, 3, 10_000L, false, false, null));

        assertThrows(IllegalArgumentException.class,
                () -> new AXStepOutcome.Continue<String>("", "q=1"));
        assertThrows(IllegalArgumentException.class,
                () -> new AXStepOutcome.Finished<String>(null));

        assertThrows(IllegalArgumentException.class,
                () -> new AXRunOutcome<String>(AXRunTermination.COMPLETED, 1, 0L, null, null, null, AXRunExpiry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunOutcome<String>(AXRunTermination.CANCELLED, 0, 0L, null, null, null, AXRunExpiry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new AXRunOutcome<String>(AXRunTermination.FAILED, 0, 0L, null, null, null, AXRunExpiry.NONE));
    }
}
