package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPrimitiveResultPayload(
        String requestId,
        String queryType,
        String status,
        int tokenCount,
        List<EmbedResultPayload> embedResults,
        LLMRuntimeSnapshotPayload runtimeSnapshot,
        String errorCode,
        String errorMessage
) implements ITianshuPayload {
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public LLMPrimitiveResultPayload {
        requestId = normalize(requestId, "llm.primitive.query");
        queryType = normalize(queryType, LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS);
        status = normalizeStatus(status);
        tokenCount = Math.max(0, tokenCount);
        embedResults = embedResults == null ? List.of() : List.copyOf(embedResults);
        runtimeSnapshot = runtimeSnapshot == null ? LLMRuntimeSnapshotPayload.unavailable() : runtimeSnapshot;
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
    }

    public static LLMPrimitiveResultPayload tokenCount(String requestId, int tokenCount) {
        return new LLMPrimitiveResultPayload(requestId, LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT, STATUS_COMPLETED, tokenCount, List.of(), LLMRuntimeSnapshotPayload.unavailable(), null, null);
    }

    public static LLMPrimitiveResultPayload embed(String requestId, List<EmbedResultPayload> embedResults) {
        return new LLMPrimitiveResultPayload(requestId, LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED, STATUS_COMPLETED, 0, embedResults, LLMRuntimeSnapshotPayload.unavailable(), null, null);
    }

    public static LLMPrimitiveResultPayload runtime(String requestId, LLMRuntimeSnapshotPayload runtimeSnapshot) {
        return new LLMPrimitiveResultPayload(requestId, LLMPrimitiveQueryPayload.QUERY_TYPE_STATUS, STATUS_COMPLETED, 0, List.of(), runtimeSnapshot, null, null);
    }

    public static LLMPrimitiveResultPayload failed(String requestId, String queryType, String errorCode, String errorMessage) {
        return new LLMPrimitiveResultPayload(requestId, queryType, STATUS_FAILED, 0, List.of(), LLMRuntimeSnapshotPayload.unavailable(), errorCode, errorMessage);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case STATUS_COMPLETED, STATUS_FAILED -> normalized;
            default -> STATUS_FAILED;
        };
    }

    public record EmbedResultPayload(
            String text,
            int dimension,
            float[] vector,
            String embeddingModelName,
            String embeddingNamespace
    ) implements ITianshuPayload {
        public EmbedResultPayload {
            text = text == null ? "" : text;
            dimension = Math.max(0, dimension);
            vector = vector == null ? new float[0] : vector.clone();
            embeddingModelName = embeddingModelName == null ? "" : embeddingModelName.trim();
            embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace.trim();
        }

        public static EmbedResultPayload of(String text, float[] vector, boolean includeVector) {
            return of(text, vector, includeVector, "", "");
        }

        public static EmbedResultPayload of(String text, float[] vector, boolean includeVector, String embeddingModelName, String embeddingNamespace) {
            int dimension = vector == null ? 0 : vector.length;
            return new EmbedResultPayload(text, dimension, includeVector ? vector : new float[0], embeddingModelName, embeddingNamespace);
        }
    }
}
