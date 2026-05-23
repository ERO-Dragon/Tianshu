package com.rheinmetal.tianshu.function.auxilium.output;

public record MemoryUpdateCandidate(
        MemoryUpdateTarget target,
        String text,
        String source,
        int confidence,
        String requestKey,
        long createdAt
) {
    public MemoryUpdateCandidate {
        target = target == null ? MemoryUpdateTarget.WORLD_CONVERSATION_SUMMARY : target;
        text = text == null ? "" : text.trim();
        source = source == null || source.isBlank() ? "AX_output" : source.trim();
        confidence = Math.max(0, Math.min(100, confidence));
        requestKey = requestKey == null || requestKey.isBlank() ? "AX.request" : requestKey.trim();
        createdAt = createdAt <= 0L ? System.currentTimeMillis() : createdAt;
    }

    public boolean isEmpty() {
        return text.isBlank();
    }
}
