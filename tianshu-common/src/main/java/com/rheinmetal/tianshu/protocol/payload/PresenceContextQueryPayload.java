package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record PresenceContextQueryPayload(
        String requestId,
        String sessionId,
        String turnId,
        String playerId,
        String worldId,
        String dimensionId,
        String userText,
        List<String> focusIds,
        long createdAtMillis,
        List<String> requestedFactIds
) implements ITianshuPayload {
    public PresenceContextQueryPayload(
            String requestId,
            String sessionId,
            String turnId,
            String playerId,
            String worldId,
            String dimensionId,
            String userText,
            List<String> focusIds,
            long createdAtMillis
    ) {
        this(requestId, sessionId, turnId, playerId, worldId, dimensionId, userText, focusIds, createdAtMillis, List.of());
    }

    public PresenceContextQueryPayload {
        requestId = clean(requestId, "presence.context.query");
        sessionId = clean(sessionId, "");
        turnId = clean(turnId, "");
        playerId = clean(playerId, "");
        worldId = clean(worldId, "");
        dimensionId = clean(dimensionId, "");
        userText = clean(userText, "");
        focusIds = focusIds == null ? List.of() : List.copyOf(focusIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        createdAtMillis = createdAtMillis <= 0L ? System.currentTimeMillis() : createdAtMillis;
        requestedFactIds = requestedFactIds == null ? List.of() : List.copyOf(requestedFactIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
