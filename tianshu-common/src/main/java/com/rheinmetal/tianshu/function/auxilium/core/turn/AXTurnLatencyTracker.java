package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.auxilium.AXModule;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Emits bounded, content-free timing milestones for one AX turn.
 * It is deliberately independent from protocol and platform APIs.
 */
final class AXTurnLatencyTracker {
    private static final AXTurnLatencyTracker NOOP = new AXTurnLatencyTracker(
            DiagnosticSink.NOOP, System::nanoTime, "", "", ""
    );
    private final DiagnosticSink sink;
    private final LongSupplier nanoTime;
    private final long startedAtNanos;
    private final String sessionId;
    private final String requestId;
    private final String turnId;
    private final EnumSet<AXTurnLatencyStage> emitted = EnumSet.noneOf(AXTurnLatencyStage.class);
    private AXTurnLatencyStage lastStage;

    AXTurnLatencyTracker(
            DiagnosticSink sink,
            LongSupplier nanoTime,
            String sessionId,
            String requestId,
            String turnId
    ) {
        this.sink = sink == null ? DiagnosticSink.NOOP : sink;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.startedAtNanos = this.nanoTime.getAsLong();
        this.sessionId = clean(sessionId);
        this.requestId = clean(requestId);
        this.turnId = clean(turnId);
    }

    static AXTurnLatencyTracker noop() {
        return NOOP;
    }

    synchronized void mark(AXTurnLatencyStage stage) {
        if (stage == null || !emitted.add(stage)) {
            return;
        }
        if (lastStage != null && stage.ordinal() < lastStage.ordinal()) {
            emitted.remove(stage);
            return;
        }
        lastStage = stage;
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedAtNanos);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("sessionId", sessionId);
        attributes.put("requestId", requestId);
        attributes.put("turnId", turnId);
        attributes.put("stage", stage.name());
        attributes.put("elapsedMs", Long.toString(elapsedNanos / 1_000_000L));
        sink.publish(DiagnosticEvent.now(
                AXModule.MODULE_ID,
                stage.name(),
                DiagnosticSeverity.DEBUG,
                DiagnosticPrivacy.REDACTED,
                attributes
        ));
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
