package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPromptResultPayload(
        String requestId,
        String status,
        String text,
        String errorCode,
        String errorMessage,
        List<RagHitPayload> ragHits,
        TokenUsagePayload usage
) implements ITianshuPayload {

    public static final LLMPromptResultPayload SUCCESS_EMPTY = new LLMPromptResultPayload(
            "", "COMPLETED", "", null, null, List.of(), TokenUsagePayload.empty()
    );

    public LLMPromptResultPayload {
        requestId = normalize(requestId);
        status = normalizeStatus(status);
        text = text == null ? "" : text;
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        ragHits = ragHits != null ? List.copyOf(ragHits) : List.of();
        usage = usage == null ? TokenUsagePayload.empty() : usage;
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
        return completed(requestId, text, List.of(), TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload completed(String requestId, String text, List<RagHitPayload> ragHits) {
        return completed(requestId, text, ragHits, TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload completed(String requestId, String text, List<RagHitPayload> ragHits, TokenUsagePayload usage) {
        return new LLMPromptResultPayload(requestId, "COMPLETED", text, null, null, ragHits, usage);
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text) {
        return cancelled(requestId, text, List.of(), TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text, List<RagHitPayload> ragHits) {
        return cancelled(requestId, text, ragHits, TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload cancelled(String requestId, String text, List<RagHitPayload> ragHits, TokenUsagePayload usage) {
        return new LLMPromptResultPayload(requestId, "CANCELLED", text, null, null, ragHits, usage);
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage) {
        return failed(requestId, errorCode, errorMessage, "", List.of(), TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage, String partialText) {
        return failed(requestId, errorCode, errorMessage, partialText, List.of(), TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage, String partialText, List<RagHitPayload> ragHits) {
        return failed(requestId, errorCode, errorMessage, partialText, ragHits, TokenUsagePayload.empty());
    }

    public static LLMPromptResultPayload failed(String requestId, String errorCode, String errorMessage, String partialText, List<RagHitPayload> ragHits, TokenUsagePayload usage) {
        return new LLMPromptResultPayload(requestId, "FAILED", partialText != null ? partialText : "", errorCode, errorMessage, ragHits, usage);
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

    public record TokenUsagePayload(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) implements ITianshuPayload {
        public TokenUsagePayload {
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
            int computedTotal = promptTokens + completionTokens;
            totalTokens = totalTokens > 0 ? totalTokens : computedTotal;
        }

        public static TokenUsagePayload empty() {
            return new TokenUsagePayload(0, 0, 0);
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
