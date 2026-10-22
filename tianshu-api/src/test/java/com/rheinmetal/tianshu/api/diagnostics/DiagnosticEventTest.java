package com.rheinmetal.tianshu.api.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticEventTest {
    @Test
    void copiesAttributesAndPreservesEventFields() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("stage", "decode");
        DiagnosticEvent event = new DiagnosticEvent("module.tts", "SYNTHESIS_FAILED", DiagnosticSeverity.ERROR,
                DiagnosticPrivacy.RAW_CONTENT, 42L, source);

        source.put("stage", "changed");

        assertEquals("module.tts", event.moduleId());
        assertEquals("SYNTHESIS_FAILED", event.code());
        assertEquals(DiagnosticSeverity.ERROR, event.severity());
        assertEquals(DiagnosticPrivacy.RAW_CONTENT, event.privacy());
        assertEquals(42L, event.occurredAtMillis());
        assertEquals("decode", event.attributes().get("stage"));
        assertThrows(UnsupportedOperationException.class, () -> event.attributes().put("x", "y"));
    }

    @Test
    void rejectsInvalidIdentityAndAttributeKeys() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticEvent("", "code", DiagnosticSeverity.INFO,
                DiagnosticPrivacy.PUBLIC, 1L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticEvent("module", "code", DiagnosticSeverity.INFO,
                DiagnosticPrivacy.PUBLIC, 1L, Map.of("", "value")));
    }

    @Test
    void noopSinkAcceptsEvents() {
        DiagnosticSink.NOOP.publish(DiagnosticEvent.now("module.asr", "RECOGNITION_STARTED",
                DiagnosticSeverity.DEBUG, DiagnosticPrivacy.REDACTED, Map.of()));
        assertTrue(true);
    }
}
