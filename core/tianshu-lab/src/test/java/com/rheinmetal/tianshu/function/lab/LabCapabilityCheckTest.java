package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabCapabilityCheckTest {

    @Test
    void theCheckPublishesOneDiagnosticEvent() {
        List<DiagnosticEvent> events = new ArrayList<>();
        new LabCapabilityCheck(events::add).run();

        assertEquals(1, events.size());
        DiagnosticEvent event = events.get(0);
        assertEquals("capability.self_check", event.code());
        assertTrue(event.attributes().containsKey("counts"));
        assertTrue(event.attributes().get("tool").startsWith("tool tianshu:inventory.inspect:"));
        assertTrue(event.attributes().get("runtimeSkill").startsWith("skill lab:runtime-skill: OK"));
    }
}
