package com.rheinmetal.tianshu.protocol.dialogue.payload;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record DialogueParticipantRegisterPayload(DialogueParticipantDescriptor descriptor, long timestampMillis) implements ITianshuPayload {
    public DialogueParticipantRegisterPayload {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor cannot be null");
        }
        timestampMillis = Math.max(0L, timestampMillis);
    }
}
