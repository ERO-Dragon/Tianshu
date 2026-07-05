package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;
import java.util.Locale;

public record LLMPrimitiveQueryPayload(
        String requestId,
        String queryType,
        String text,
        List<String> texts,
        List<MessageItemPayload> messages,
        List<ChunkPayload> chunks,
        Boolean includeVector,
        Boolean includeEmbeddingDetails,
        Boolean includeRuntimeDetails
) implements ITianshuPayload {
    public static final String QUERY_TYPE_TOKEN_COUNT = "TOKEN_COUNT";
    public static final String QUERY_TYPE_EMBED = "EMBED";
    public static final String QUERY_TYPE_STATUS = "STATUS";

    public LLMPrimitiveQueryPayload {
        requestId = normalize(requestId, "llm.primitive.query");
        queryType = normalizeQueryType(queryType);
        text = text == null ? "" : text;
        texts = texts == null ? List.of() : List.copyOf(texts);
        messages = messages == null ? List.of() : List.copyOf(messages);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        includeVector = includeVector != null ? includeVector : true;
        includeEmbeddingDetails = includeEmbeddingDetails != null ? includeEmbeddingDetails : true;
        includeRuntimeDetails = includeRuntimeDetails != null ? includeRuntimeDetails : true;
    }

    public static LLMPrimitiveQueryPayload tokenCount(String requestId, String text, List<MessageItemPayload> messages, List<ChunkPayload> chunks) {
        return new LLMPrimitiveQueryPayload(requestId, QUERY_TYPE_TOKEN_COUNT, text, null, messages, chunks, false, false, false);
    }

    public static LLMPrimitiveQueryPayload embed(String requestId, List<String> texts, boolean includeVector, boolean includeEmbeddingDetails) {
        return new LLMPrimitiveQueryPayload(requestId, QUERY_TYPE_EMBED, "", texts, List.of(), List.of(), includeVector, includeEmbeddingDetails, false);
    }

    public static LLMPrimitiveQueryPayload status(String requestId, boolean includeRuntimeDetails) {
        return new LLMPrimitiveQueryPayload(requestId, QUERY_TYPE_STATUS, "", List.of(), List.of(), List.of(), false, false, includeRuntimeDetails);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizeQueryType(String value) {
        if (value == null || value.isBlank()) {
            return QUERY_TYPE_STATUS;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record ChunkPayload(
            String type,
            List<MessageItemPayload> messageContent,
            List<String> ragContent,
            String uid,
            String prompt,
            Boolean useCache,
            Boolean includeRagHits,
            Integer memoryRagTokenBudget
    ) implements ITianshuPayload {
        public ChunkPayload {
            type = type == null ? "message" : type.trim().toLowerCase();
            messageContent = messageContent != null ? List.copyOf(messageContent) : List.of();
            ragContent = ragContent != null ? List.copyOf(ragContent) : List.of();
            prompt = prompt != null ? prompt.trim() : "";
            useCache = useCache != null ? useCache : true;
            includeRagHits = includeRagHits != null ? includeRagHits : true;
            memoryRagTokenBudget = memoryRagTokenBudget != null ? memoryRagTokenBudget : 1000;
        }

        public static ChunkPayload message(List<MessageItemPayload> messageContent) {
            return new ChunkPayload("message", messageContent, List.of(), "", "", true, true, 1000);
        }

        public static ChunkPayload rag(String uid, String prompt, List<String> ragContent, boolean useCache, boolean includeRagHits, int memoryRagTokenBudget) {
            return new ChunkPayload("rag", List.of(), ragContent, uid, prompt, useCache, includeRagHits, memoryRagTokenBudget);
        }
    }

    public record MessageItemPayload(
            String role,
            String content
    ) implements ITianshuPayload {
        public MessageItemPayload {
            role = role == null ? "user" : role.trim().toLowerCase();
            content = content == null ? "" : content.trim();
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
