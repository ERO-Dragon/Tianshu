package com.rheinmetal.tianshu.protocol.dialogue.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueTurnProcessingPolicyTest {
    @Test
    void defaultPolicyUsesFastInitialWindowAndLongAbsoluteLimit() {
        DialogueTurnProcessingPolicy policy = DialogueTurnProcessingPolicy.DEFAULT;

        assertEquals(10_100L, policy.processingDeadlineAt(100L));
        assertEquals(180_100L, policy.absoluteDeadlineAt(100L));
    }

    @Test
    void extensionCannotMovePastAbsoluteLimitFromSessionCreation() {
        DialogueTurnProcessingPolicy policy = DialogueTurnProcessingPolicy.DEFAULT;

        long deadline = policy.extendDeadlineAt(100L, 175_000L, 60_000L);

        assertEquals(180_100L, deadline);
    }
}
