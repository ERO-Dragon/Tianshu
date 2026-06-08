package com.rheinmetal.tianshu.function.ia.payload;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DialogueSessionEventPayloadPrivacyTest {
    @Test
    void sessionEventPayloadDoesNotExposeDialogueBodyOrGenerationData() {
        Set<String> fields = Arrays.stream(DialogueSessionEventPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertFalse(fields.contains("repairedText"));
        assertFalse(fields.contains("normalizedText"));
        assertFalse(fields.contains("prompt"));
        assertFalse(fields.contains("response"));
        assertFalse(fields.contains("contextSnapshot"));
        assertFalse(fields.contains("matchedWakeWords"));
        assertFalse(fields.contains("matchedItemIds"));
        assertFalse(fields.contains("matchedEntityRefs"));
    }
}
