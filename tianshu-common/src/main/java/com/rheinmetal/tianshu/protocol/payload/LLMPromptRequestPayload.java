package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;
import java.util.Objects;

public record LLMPromptRequestPayload(
        String requestId,
        Integer maxTokens,
        Float temperature,
        Boolean stream,
        Boolean thinking,
        String lane,
        Integer taskPriority,
        Boolean taskPreemptible,
        List<ChunkPayload> chunks
) implements ITianshuPayload {

    public static final LLMPromptRequestPayload EMPTY = new LLMPromptRequestPayload(
            "llm.request", 0, 0.7f, false, false, "CHAT", 0, false, List.of()
    );

    public LLMPromptRequestPayload {
        requestId = normalize(requestId);
        maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : 0;
        temperature = normalizeTemperature(temperature);
        stream = stream != null ? stream : false;
        thinking = thinking != null ? thinking : false;
        lane = normalizeLane(lane);
        taskPriority = clampPriority(taskPriority);
        taskPreemptible = taskPreemptible != null ? taskPreemptible : false;
        chunks = chunks != null ? List.copyOf(chunks) : List.of();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "llm.request" : value.trim();
    }

    private static Float normalizeTemperature(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 2f) {
            return 0.7f;
        }
        return value;
    }

    private static String normalizeLane(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return "TASK".equals(normalized) ? "TASK" : "CHAT";
    }

    private static Integer clampPriority(Integer value) {
        if (value == null) return 0;
        return Math.max(-1000, Math.min(1000, value));
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

        public static ChunkPayload message(List<MessageItemPayload> messages) {
            return new ChunkPayload("message", messages, null, null, null, null, null, null);
        }

        public static ChunkPayload rag(String uid, List<String> contents) {
            return new ChunkPayload("rag", null, contents, uid, "", true, true, 1000);
        }

        public static ChunkPayload rag(String uid, String prompt, List<String> contents, boolean useCache, boolean includeRagHits, int memoryTokenBudget) {
            return new ChunkPayload("rag", null, contents, uid, prompt, useCache, includeRagHits, memoryTokenBudget);
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

        public static MessageItemPayload of(String role, String content) {
            return new MessageItemPayload(role, content);
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