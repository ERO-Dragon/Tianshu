package com.rheinmetal.tianshu.function.ia.diagnostics;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DialogueDiagnosticsSnapshotPrivacyTest {
    @Test
    void diagnosticsSnapshotDoesNotExposeDialogueBodyOrGenerationData() {
        Set<String> snapshotFields = Arrays.stream(DialogueDiagnosticsSnapshot.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        Set<String> sessionFields = Arrays.stream(DialogueSession.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertFalse(snapshotFields.contains("repairedText"));
        assertFalse(snapshotFields.contains("normalizedText"));
        assertFalse(snapshotFields.contains("prompt"));
        assertFalse(snapshotFields.contains("response"));
        assertFalse(snapshotFields.contains("contextSnapshot"));
        assertFalse(sessionFields.contains("repairedText"));
        assertFalse(sessionFields.contains("normalizedText"));
        assertFalse(sessionFields.contains("prompt"));
        assertFalse(sessionFields.contains("response"));
        assertFalse(sessionFields.contains("contextSnapshot"));
    }
}
