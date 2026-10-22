package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 终态分类与播报资源键（设计 §6.2 / §6.3）：判定只决定"念哪一句"，绝不决定"是否登记"。
 */
class AXDelegateDeliveryRulesTest {

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
    }
}
