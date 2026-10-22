package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终态分类与播报门槛（设计 §6.2 / §6.3）：判定只决定"能不能现在开口"，绝不决定"是否登记"。
 */
class AXDelegateDeliveryRulesTest {

    private final AXDelegateBroadcastGate gate = new AXDelegateBroadcastGate();

    @Test
    void everyTerminationMapsToExactlyOneKind() {
        assertEquals(AXDelegateOutcomeKind.OK, AXDelegateOutcomeKind.of(AXRunTermination.COMPLETED));
        assertEquals(AXDelegateOutcomeKind.TIMEOUT, AXDelegateOutcomeKind.of(AXRunTermination.ABORT_TIMEOUT));
        assertEquals(AXDelegateOutcomeKind.CANCELLED, AXDelegateOutcomeKind.of(AXRunTermination.CANCELLED));
        assertEquals(AXDelegateOutcomeKind.FAILED, AXDelegateOutcomeKind.of(AXRunTermination.FAILED));
        assertEquals(AXDelegateOutcomeKind.FAILED, AXDelegateOutcomeKind.of(AXRunTermination.ABORT_MAX_STEPS));
        assertEquals(AXDelegateOutcomeKind.FAILED, AXDelegateOutcomeKind.of(AXRunTermination.ABORT_NO_PROGRESS));
    }

    @Test
    void eventLineKindsAreTheFourDocumentedOnes() {
        assertEquals("delegate_ok", AXDelegateOutcomeKind.OK.code());
        assertEquals("delegate_failed", AXDelegateOutcomeKind.FAILED.code());
        assertEquals("delegate_timeout", AXDelegateOutcomeKind.TIMEOUT.code());
        assertEquals("delegate_cancelled", AXDelegateOutcomeKind.CANCELLED.code());
    }

    @Test
    void speechKeysFollowTheResourceKeyDiscipline() {
        assertNull(AXDelegateOutcomeKind.OK.speechKey(), "结论播报读正文本身，不用兜底句");
        assertEquals("ax.runtime.delegate.failed", AXDelegateOutcomeKind.FAILED.speechKey());
        assertEquals("ax.runtime.delegate.timeout", AXDelegateOutcomeKind.TIMEOUT.speechKey());
        assertEquals("ax.runtime.delegate.cancelled", AXDelegateOutcomeKind.CANCELLED.speechKey());
        assertEquals("ax.runtime.delegate.ack", AXDelegateOutcomeKind.ACK_SPEECH_KEY);
        assertFalse(AXDelegateOutcomeKind.OK.mustSpeakFallback());
        assertTrue(AXDelegateOutcomeKind.TIMEOUT.mustSpeakFallback());
    }

    @Test
    void idlePlayerSpeaksImmediately() {
        assertEquals(AXDelegateBroadcastGate.Mode.IMMEDIATE,
                gate.decide(AXDelegateBroadcastGate.Context.idle(), false));
        assertEquals(AXDelegateBroadcastGate.Mode.IMMEDIATE,
                gate.decide(AXDelegateBroadcastGate.Context.idle(), true));
    }

    @Test
    void runningChatTurnOrOngoingBroadcastQueues() {
        assertEquals(AXDelegateBroadcastGate.Mode.QUEUED,
                gate.decide(new AXDelegateBroadcastGate.Context(true, false, false), false));
        assertEquals(AXDelegateBroadcastGate.Mode.QUEUED,
                gate.decide(new AXDelegateBroadcastGate.Context(false, true, false), false));
    }

    @Test
    void combatOnlyAppendsForConclusions() {
        assertEquals(AXDelegateBroadcastGate.Mode.SUPPRESSED,
                gate.decide(new AXDelegateBroadcastGate.Context(false, false, true), false));
    }

    @Test
    void combatNeverSilencesAFailureLine() {
        assertEquals(AXDelegateBroadcastGate.Mode.QUEUED,
                gate.decide(new AXDelegateBroadcastGate.Context(false, false, true), true),
                "失败 / 超时 / 取消必须出声一句，战斗中只降级为排队（§6.2）");
    }

    @Test
    void nullContextIsTreatedAsIdle() {
        assertEquals(AXDelegateBroadcastGate.Mode.IMMEDIATE, gate.decide(null, false));
    }
}
