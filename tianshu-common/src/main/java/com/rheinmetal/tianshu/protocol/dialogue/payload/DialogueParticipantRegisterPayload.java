package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueParticipantRegisterPayload(DialogueParticipantDescriptor descriptor, String capabilitySummary, long timestampMillis) implements ITianshuPayload {
    public DialogueParticipantRegisterPayload(DialogueParticipantDescriptor descriptor, long timestampMillis) {
        this(descriptor, "", timestampMillis);
    }

    public DialogueParticipantRegisterPayload {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor cannot be null");
        }
        capabilitySummary = capabilitySummary == null ? "" : capabilitySummary.trim();
        timestampMillis = Math.max(0L, timestampMillis);
    }
}
