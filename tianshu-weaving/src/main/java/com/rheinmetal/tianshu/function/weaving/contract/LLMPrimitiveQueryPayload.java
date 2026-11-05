package com.rheinmetal.tianshu.function.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;
import java.util.Locale;

/**
 * Measurement query that does not generate: token estimate, embedding, or a runtime snapshot.
 */
public record LLMPrimitiveQueryPayload(
        String requestId,
        String queryType,
        String text,
        List<String> texts,
        List<MessageItemPayload> messages,
        Boolean includeVector,
        Boolean includeEmbeddingDetails
) implements ITianshuPayload {
    public static final String QUERY_TYPE_TOKEN_COUNT = "TOKEN_COUNT";
    public static final String QUERY_TYPE_EMBED = "EMBED";
    public static final String QUERY_TYPE_STATUS = "STATUS";

    public LLMPrimitiveQueryPayload {
        requestId = requestId == null || requestId.isBlank() ? "llm.primitive.query" : requestId.trim();
        queryType = normalizeQueryType(queryType);
        text = text == null ? "" : text;
        texts = texts == null ? List.of() : List.copyOf(texts);
        messages = messages == null ? List.of() : List.copyOf(messages);
        includeVector = includeVector != null && includeVector;
        includeEmbeddingDetails = includeEmbeddingDetails != null && includeEmbeddingDetails;
    }

    public static LLMPrimitiveQueryPayload tokenCount(
            String requestId,
            String text,
            List<MessageItemPayload> messages
    ) {
        return new LLMPrimitiveQueryPayload(
                requestId, QUERY_TYPE_TOKEN_COUNT, text, List.of(), messages, false, false
        );
    }

    public static LLMPrimitiveQueryPayload embed(
            String requestId,
            List<String> texts,
            boolean includeVector,
            boolean includeEmbeddingDetails
    ) {
        return new LLMPrimitiveQueryPayload(
                requestId, QUERY_TYPE_EMBED, "", texts, List.of(), includeVector, includeEmbeddingDetails
        );
    }

    public static LLMPrimitiveQueryPayload status(String requestId) {
        return new LLMPrimitiveQueryPayload(requestId, QUERY_TYPE_STATUS, "", List.of(), List.of(), false, false);
    }

    private static String normalizeQueryType(String value) {
        if (value == null || value.isBlank()) {
            return QUERY_TYPE_STATUS;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record MessageItemPayload(
            String role,
            String content
    ) implements ITianshuPayload {
        public MessageItemPayload {
            role = role == null ? "user" : role.trim().toLowerCase();
            content = content == null ? "" : content;
        }

        public static MessageItemPayload system(String content) {
            return new MessageItemPayload("system", content);
        }

        public static MessageItemPayload user(String content) {
            return new MessageItemPayload("user", content);
        }

        public static MessageItemPayload assistant(String content) {
            return new MessageItemPayload("assistant", content);
        }
    }
}
