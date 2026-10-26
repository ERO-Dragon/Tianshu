package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;

import java.util.Objects;

public record AXOutputContext(
        String sessionId,
        String requestId,
        String turnId,
        String playerId,
        long startedAtMillis
) {
    public AXOutputContext {
        sessionId = clean(sessionId);
        requestId = clean(requestId);
        turnId = clean(turnId);
        playerId = clean(playerId);
        startedAtMillis = Math.max(0L, startedAtMillis);
    }

    public static AXOutputContext from(AXDialogueInput input) {
        Objects.requireNonNull(input, "input");
        return new AXOutputContext(
                input.sessionId(),
                input.requestId(),
                input.turnId(),
                input.playerId(),
                System.currentTimeMillis()
        );
    }

    public int ttsTurnId() {
        return numericId(turnId, requestId);
    }

    public long ttsSessionId() {
        return numericId(sessionId, requestId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static int numericId(String primary, String fallback) {
        String value = !clean(primary).isBlank() ? clean(primary) : clean(fallback);
        if (value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.abs(parsed)));
        } catch (NumberFormatException ignored) {
            return Math.abs(value.hashCode());
        }
    }
}
