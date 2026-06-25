package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPromptResultPayload(
        String requestId,
        String status,
        String text,
        String errorCode,
        String errorMessage,
        List<RagHitPayload> ragHits
) implements ITianshuPayload {

    public static final LLMPromptResultPayload SUCCESS_EMPTY = new LLMPromptResultPayload(
            "", "COMPLETED", "", null, null, List.of()
    );

    public LLMPromptResultPayload {
        requestId = normalize(requestId);
        status = normalizeStatus(status);
        text = text == null ? "" : text;
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case "COMPLETED", "CANCELLED", "FAILED" -> normalized;
            default -> "FAILED";
        };
    }

    public static LLMPromptResultPayload completed(String requestId, String text) {
        return new LLMPromptResultPayload(requestId, "COMPLETED", text, null, null, List.of());
    }

    public static LLMPromptResultPayload completed(String requestId, String text, List<RagHitPayload> ragHits) {
        return new LLMPromptResultPayload(requestId, "COMPLETED", text, null, null, ragHits);
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text) {
        return new LLMPromptResultPayload(requestId, "CANCELLED", text, null, null, List.of());
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text, List<RagHitPayload> ragHits) {
        return new LLMPromptResultPayload(requestId, "CANCELLED", text, null, null, ragHits);
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage) {
        return new LLMPromptResultPayload(requestId, "FAILED", "", errorCode, errorMessage, List.of());
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage, String partialText) {
        return new LLMPromptResultPayload(requestId, "FAILED", partialText != null ? partialText : "", errorCode, errorMessage, List.of());
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage, String partialText, List<RagHitPayload> ragHits) {
        return new LLMPromptResultPayload(requestId, "FAILED", partialText != null ? partialText : "", errorCode, errorMessage, ragHits);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    public record HitEntry(
            double score,
            String content
    ) implements ITianshuPayload {
        public HitEntry {
            content = content == null ? "" : content;
            score = Double.isNaN(score) || Double.isInfinite(score) ? 0.0 : score;
        }

        public static HitEntry of(double score, String content) {
            return new HitEntry(score, content);
        }
    }

    public record RagHitPayload(
            String uid,
            boolean globalRagCache,
            List<HitEntry> hits
    ) implements ITianshuPayload {
        public RagHitPayload {
            uid = uid == null ? "" : uid.trim();
            hits = hits != null ? List.copyOf(hits) : List.of();
        }

        public RagHitPayload(String uid, List<HitEntry> hits) {
            this(uid, false, hits);
        }

        public static RagHitPayload of(String uid, List<HitEntry> hits) {
            return new RagHitPayload(uid, false, hits);
        }

        public static RagHitPayload of(String uid, boolean globalRagCache, List<HitEntry> hits) {
            return new RagHitPayload(uid, globalRagCache, hits);
        }
    }
}
