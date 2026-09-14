package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AXTurnLatencyTrackerTest {
    @Test
    void publishesEachStageOnceWithStableTurnIdentity() {
        List<DiagnosticEvent> events = new ArrayList<>();
        AXTurnLatencyTracker tracker = new AXTurnLatencyTracker(
                events::add,
                () -> 1_000_000L,
                "session-1",
                "request-1",
                "turn-1"
        );

        tracker.mark(AXTurnLatencyStage.IA_DELIVERY);
        tracker.mark(AXTurnLatencyStage.IA_DELIVERY);
        tracker.mark(AXTurnLatencyStage.PROMPT_READY);

        assertEquals(List.of("IA_DELIVERY", "PROMPT_READY"), events.stream()
                .map(DiagnosticEvent::code)
                .toList());
        assertTrue(events.stream().allMatch(event ->
                "session-1".equals(event.attributes().get("sessionId"))
                        && "request-1".equals(event.attributes().get("requestId"))
                        && "turn-1".equals(event.attributes().get("turnId"))
                        && !event.attributes().containsKey("text")));
    }

    @Test
    void ignoresOutOfOrderStagesWithoutBlockingLaterStages() {
        List<DiagnosticEvent> events = new ArrayList<>();
        AXTurnLatencyTracker tracker = new AXTurnLatencyTracker(
                events::add,
                () -> 2_000_000L,
                "session",
                "request",
                "turn"
        );

        tracker.mark(AXTurnLatencyStage.LLM_FIRST_TOKEN);
        tracker.mark(AXTurnLatencyStage.PROMPT_READY);

        assertEquals(List.of("LLM_FIRST_TOKEN"), events.stream()
                .map(DiagnosticEvent::code)
                .toList());
    }
}
