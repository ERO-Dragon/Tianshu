package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunTermination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 终态分类与播报资源键（设计 §6.2 / §6.3）：判定只决定"念哪一句"，绝不决定"是否登记"。
 */
class DubheDelegateDeliveryRulesTest {

    @Test
    void everyTerminationMapsToExactlyOneKind() {
        assertEquals(DubheDelegateOutcomeKind.OK, DubheDelegateOutcomeKind.of(DubheRunTermination.COMPLETED));
        assertEquals(DubheDelegateOutcomeKind.TIMEOUT, DubheDelegateOutcomeKind.of(DubheRunTermination.ABORT_TIMEOUT));
        assertEquals(DubheDelegateOutcomeKind.CANCELLED, DubheDelegateOutcomeKind.of(DubheRunTermination.CANCELLED));
        assertEquals(DubheDelegateOutcomeKind.FAILED, DubheDelegateOutcomeKind.of(DubheRunTermination.FAILED));
        assertEquals(DubheDelegateOutcomeKind.FAILED, DubheDelegateOutcomeKind.of(DubheRunTermination.ABORT_MAX_STEPS));
        assertEquals(DubheDelegateOutcomeKind.FAILED, DubheDelegateOutcomeKind.of(DubheRunTermination.ABORT_NO_PROGRESS));
    }

    @Test
    void eventLineKindsAreTheFourDocumentedOnes() {
        assertEquals("delegate_ok", DubheDelegateOutcomeKind.OK.code());
        assertEquals("delegate_failed", DubheDelegateOutcomeKind.FAILED.code());
        assertEquals("delegate_timeout", DubheDelegateOutcomeKind.TIMEOUT.code());
        assertEquals("delegate_cancelled", DubheDelegateOutcomeKind.CANCELLED.code());
    }

    @Test
    void speechKeysFollowTheResourceKeyDiscipline() {
        assertNull(DubheDelegateOutcomeKind.OK.speechKey(), "结论播报读正文本身，不用兜底句");
        assertEquals("dubhe.runtime.delegate.failed", DubheDelegateOutcomeKind.FAILED.speechKey());
        assertEquals("dubhe.runtime.delegate.timeout", DubheDelegateOutcomeKind.TIMEOUT.speechKey());
        assertEquals("dubhe.runtime.delegate.cancelled", DubheDelegateOutcomeKind.CANCELLED.speechKey());
        assertEquals("dubhe.runtime.delegate.ack", DubheDelegateOutcomeKind.ACK_SPEECH_KEY);
    }
}
