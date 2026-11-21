package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
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

class DubheAgentLoopTest {

    @Test
    void simpleRunCompletesInOneStepWithoutTools() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.simple(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        DubheRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            assertEquals(0, context.stepIndex(), "主对话第一步就是唯一一步");
            assertEquals(DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS, context.remainingMillis());
            assertFalse(context.policy().toolsAllowed(), "主对话不带 tools[]（设计 §4.2）");
            assertEquals(CancellationScope.SELF_ONLY, context.policy().cancellationScope());
            return new DubheStepOutcome.Finished<String>("暮色森林从要塞旁的传送门进");
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertEquals(1, outcome.steps());
        assertEquals("暮色森林从要塞旁的传送门进", outcome.result());
        assertNull(outcome.cancellation());
        assertNull(outcome.failure());
        assertEquals(1, calls.get());
    }

    @Test
    void repeatedContinuationHitsSimpleStepValveAndDegrades() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.simple(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        DubheRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            return new DubheStepOutcome.Continue<String>("prompt.reask", "text=" + context.stepIndex());
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.ABORT_MAX_STEPS, outcome.termination());
        assertEquals(DubheRunPolicy.SIMPLE_MAX_STEPS, outcome.steps());
        assertEquals(3, calls.get(), "安全阀在请求之前判定，撞阀后不再多发一次请求");
        assertNull(outcome.result(), "超步数返回空结果，调用方走设计 §4.1 的降级路径");
    }

    @Test
    void simpleRunAbortsWhenWallClockPassesDeadline() {
        AtomicLong clockNanos = new AtomicLong();
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.simple(), clockNanos::get);

        DubheRunOutcome<String> outcome = loop.run(context -> {
            clockNanos.addAndGet(DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS * 1_000_000L);
            return new DubheStepOutcome.Continue<String>("wiki.search", "q=" + context.stepIndex());
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.ABORT_TIMEOUT, outcome.termination());
        assertEquals(1, outcome.steps());
        assertEquals(DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS, outcome.elapsedMillis());
        assertNull(outcome.result());
    }

    @Test
    void cancellationBeforeTheFirstStepReturnsTheReleaseReason() {
        DubheTurnCancellation cancellation = DubheTurnCancellation.playerInterrupted("玩家又说话了");
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.complex(), () -> 0L);
        AtomicInteger calls = new AtomicInteger();

        DubheRunOutcome<String> outcome = loop.run(context -> {
            calls.incrementAndGet();
            return new DubheStepOutcome.Finished<String>("不该走到这里");
        }, DubheRunCancellation.of(() -> true, () -> cancellation));

        assertEquals(DubheRunTermination.CANCELLED, outcome.termination());
        assertEquals(0, outcome.steps());
        assertEquals(0, calls.get(), "取消后一步都不许发");
        assertSame(cancellation, outcome.cancellation());
        assertNull(outcome.result());
    }

    @Test
    void complexRunAbortsAfterThreeIdenticalSteps() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.complex(), () -> 0L);

        DubheRunOutcome<String> outcome = loop.run(
                context -> new DubheStepOutcome.Continue<String>("wiki.search", "{\"q\":\"twilight forest portal\"}"),
                DubheRunCancellation.none());

        assertEquals(DubheRunTermination.ABORT_NO_PROGRESS, outcome.termination());
        assertEquals(DubheNoProgressDetector.STREAK_LIMIT, outcome.steps());
        assertEquals(3, outcome.steps());
        assertNull(outcome.result());
    }

    @Test
    void complexRunToleratesMoreStepsThanSimpleBeforeConverging() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.complex(), () -> 0L);

        DubheRunOutcome<String> outcome = loop.run(context -> {
            if (context.stepIndex() < 5) {
                return new DubheStepOutcome.Continue<String>("recipe.lookup", "id=" + context.stepIndex());
            }
            return new DubheStepOutcome.Finished<String>("四个圆石摆成 2x2 得到石砖");
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertEquals(6, outcome.steps(), "Complex 的阀是 25 步，Simple 的 3 步不该套用到这里");
        assertEquals("四个圆石摆成 2x2 得到石砖", outcome.result());
    }

    @Test
    void stepFailureSurfacesAsFailedRun() {
        DubheAgentLoop loop = new DubheAgentLoop(DubheRunPolicy.complex(), () -> 0L);
        IllegalStateException failure = new IllegalStateException("wiki 网关超时");

        DubheRunOutcome<String> outcome = loop.run(context -> {
            throw failure;
        }, DubheRunCancellation.none());

        assertEquals(DubheRunTermination.FAILED, outcome.termination());
        assertEquals(0, outcome.steps());
        assertSame(failure, outcome.failure());
        assertNull(outcome.result());
    }

    @Test
    void presetsCarryTheDesignNumbers() {
        DubheRunPolicy simple = DubheRunPolicy.simple();
        assertEquals(DubheRunPolicy.SIMPLE_ID, simple.id());
        assertEquals(3, simple.maxSteps());
        assertEquals(DubheRunPolicy.SIMPLE_TIMEOUT_MILLIS, simple.timeoutMillis());
        assertFalse(simple.toolsAllowed());
        assertFalse(simple.noProgressDetection());
        assertEquals(CancellationScope.SELF_ONLY, simple.cancellationScope());

        DubheRunPolicy complex = DubheRunPolicy.complex();
        assertEquals(DubheRunPolicy.COMPLEX_ID, complex.id());
        assertEquals(25, complex.maxSteps());
        assertEquals(120_000L, complex.timeoutMillis());
        assertTrue(complex.toolsAllowed());
        assertTrue(complex.noProgressDetection());
        assertEquals(CancellationScope.CHILDREN, complex.cancellationScope());
    }

    @Test
    void degeneratePolicyAndOutcomeAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunPolicy(" ", 3, 10_000L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunPolicy(DubheRunPolicy.SIMPLE_ID, 0, 10_000L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunPolicy(DubheRunPolicy.SIMPLE_ID, 3, 0L, false, false, CancellationScope.SELF_ONLY));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunPolicy(DubheRunPolicy.SIMPLE_ID, 3, 10_000L, false, false, null));

        assertThrows(IllegalArgumentException.class,
                () -> new DubheStepOutcome.Continue<String>("", "q=1"));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheStepOutcome.Finished<String>(null));

        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunOutcome<String>(DubheRunTermination.COMPLETED, 1, 0L, null, null, null, DubheRunExpiry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunOutcome<String>(DubheRunTermination.CANCELLED, 0, 0L, null, null, null, DubheRunExpiry.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> new DubheRunOutcome<String>(DubheRunTermination.FAILED, 0, 0L, null, null, null, DubheRunExpiry.NONE));
    }
}
