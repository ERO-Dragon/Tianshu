package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMPromptRequestPayload(
        String requestId,
        Integer maxTokens,
        Float temperature,
        Integer topK,
        Float topP,
        Float minP,
        Float penaltyRepeat,
        Float penaltyFreq,
        Float penaltyPresent,
        Integer penaltyLastN,
        Boolean stream,
        Boolean thinking,
        Boolean captureThinkingContent,
        String lane,
        Integer taskPriority,
        Boolean taskPreemptible,
        List<ChunkPayload> chunks,
        String dialogueSessionId,
        String requesterModuleId,
        String requesterParticipantId,
        String dialogueTurnId,
        InferencePolicyPayload inferencePolicy,
        String toolsJson
) implements ITianshuPayload {
    public static final int MIN_TASK_PRIORITY = 0;
    public static final int MAX_TASK_PRIORITY = 1000;

    public static final LLMPromptRequestPayload EMPTY = new LLMPromptRequestPayload(
            "llm.request", 0, null, false, false, "CHAT", 0, false, List.of()
    );

    public LLMPromptRequestPayload(
            String requestId,
            Integer maxTokens,
            Float temperature,
            Boolean stream,
            Boolean thinking,
            Boolean captureThinkingContent,
            String lane,
            Integer taskPriority,
            Boolean taskPreemptible,
            List<ChunkPayload> chunks,
            String dialogueSessionId,
            String requesterModuleId,
            String requesterParticipantId,
            String dialogueTurnId,
            InferencePolicyPayload inferencePolicy
    ) {
        this(requestId, maxTokens, temperature, null, null, null, null, null, null, null, stream, thinking, captureThinkingContent, lane, taskPriority,
                taskPreemptible, chunks, dialogueSessionId, requesterModuleId, requesterParticipantId, dialogueTurnId,
                inferencePolicy, null);
    }

    public LLMPromptRequestPayload(
            String requestId,
            Integer maxTokens,
            Float temperature,
            Boolean stream,
            Boolean thinking,
            String lane,
            Integer taskPriority,
            Boolean taskPreemptible,
            List<ChunkPayload> chunks,
            String dialogueSessionId,
            String requesterModuleId,
            String requesterParticipantId,
            String dialogueTurnId
    ) {
        this(requestId, maxTokens, temperature, null, null, null, null, null, null, null, stream, thinking, false, lane, taskPriority, taskPreemptible, chunks,
                dialogueSessionId, requesterModuleId, requesterParticipantId, dialogueTurnId, null, null);
    }

    public LLMPromptRequestPayload(
            String requestId,
            Integer maxTokens,
            Float temperature,
            Boolean stream,
            Boolean thinking,
            String lane,
            Integer taskPriority,
            Boolean taskPreemptible,
            List<ChunkPayload> chunks
    ) {
        this(requestId, maxTokens, temperature, null, null, null, null, null, null, null, stream, thinking, false, lane, taskPriority, taskPreemptible, chunks, "", "", "", "", null, null);
    }

    public LLMPromptRequestPayload(
            String requestId,
            Integer maxTokens,
            Float temperature,
            Boolean stream,
            Boolean thinking,
            Boolean captureThinkingContent,
            String lane,
            Integer taskPriority,
            Boolean taskPreemptible,
            List<ChunkPayload> chunks
    ) {
        this(requestId, maxTokens, temperature, null, null, null, null, null, null, null, stream, thinking, captureThinkingContent, lane, taskPriority, taskPreemptible, chunks, "", "", "", "", null, null);
    }

    public LLMPromptRequestPayload {
        requestId = normalize(requestId);
        maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : 0;
        temperature = normalizeTemperature(temperature);
        topK = normalizePositive(topK);
        topP = normalizeUnit(topP);
        minP = normalizeUnit(minP);
        penaltyRepeat = normalizePenalty(penaltyRepeat);
        penaltyFreq = normalizePenalty(penaltyFreq);
        penaltyPresent = normalizePenalty(penaltyPresent);
        penaltyLastN = normalizeNonNegative(penaltyLastN);
        stream = stream != null ? stream : false;
        thinking = thinking != null ? thinking : false;
        captureThinkingContent = captureThinkingContent != null ? captureThinkingContent : false;
        lane = normalizeLane(lane);
        taskPriority = clampPriority(taskPriority);
        taskPreemptible = taskPreemptible != null ? taskPreemptible : false;
        chunks = chunks != null ? List.copyOf(chunks) : List.of();
        dialogueSessionId = clean(dialogueSessionId);
        requesterModuleId = clean(requesterModuleId);
        requesterParticipantId = clean(requesterParticipantId);
        dialogueTurnId = clean(dialogueTurnId);
        inferencePolicy = inferencePolicy == null ? InferencePolicyPayload.followGlobal() : inferencePolicy;
        toolsJson = toolsJson == null || toolsJson.isBlank() ? "" : toolsJson.trim();
    }

    public LLMPromptRequestPayload withDialogueAuthorization(String sessionId, String moduleId, String participantId, String turnId) {
        return new LLMPromptRequestPayload(
                requestId,
                maxTokens,
                temperature,
                topK,
                topP,
                minP,
                penaltyRepeat,
                penaltyFreq,
                penaltyPresent,
                penaltyLastN,
                stream,
                thinking,
                captureThinkingContent,
                lane,
                taskPriority,
                taskPreemptible,
                chunks,
                sessionId,
                moduleId,
                participantId,
                turnId,
                inferencePolicy,
                toolsJson
        );
    }

    public LLMPromptRequestPayload withCaptureThinkingContent(boolean captureThinkingContent) {
        return new LLMPromptRequestPayload(
                requestId,
                maxTokens,
                temperature,
                topK,
                topP,
                minP,
                penaltyRepeat,
                penaltyFreq,
                penaltyPresent,
                penaltyLastN,
                stream,
                thinking,
                captureThinkingContent,
                lane,
                taskPriority,
                taskPreemptible,
                chunks,
                dialogueSessionId,
                requesterModuleId,
                requesterParticipantId,
                dialogueTurnId,
                inferencePolicy,
                toolsJson
        );
    }

    public LLMPromptRequestPayload withToolsJson(String toolsJson) {
        return new LLMPromptRequestPayload(
                requestId,
                maxTokens,
                temperature,
                topK,
                topP,
                minP,
                penaltyRepeat,
                penaltyFreq,
                penaltyPresent,
                penaltyLastN,
                stream,
                thinking,
                captureThinkingContent,
                lane,
                taskPriority,
                taskPreemptible,
                chunks,
                dialogueSessionId,
                requesterModuleId,
                requesterParticipantId,
                dialogueTurnId,
                inferencePolicy,
                toolsJson
        );
    }

    public LLMPromptRequestPayload withInferencePolicy(InferencePolicyPayload inferencePolicy) {
        return new LLMPromptRequestPayload(
                requestId,
                maxTokens,
                temperature,
                topK,
                topP,
                minP,
                penaltyRepeat,
                penaltyFreq,
                penaltyPresent,
                penaltyLastN,
                stream,
                thinking,
                captureThinkingContent,
                lane,
                taskPriority,
                taskPreemptible,
                chunks,
                dialogueSessionId,
                requesterModuleId,
                requesterParticipantId,
                dialogueTurnId,
                inferencePolicy,
                toolsJson
        );
    }

    public boolean hasDialogueAuthorizationContext() {
        return !dialogueSessionId.isBlank() && !requesterModuleId.isBlank() && !requesterParticipantId.isBlank();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "llm.request" : value.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static Float normalizeTemperature(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 2f) {
            return null;
        }
        return value;
    }

    private static Integer normalizePositive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static Integer normalizeNonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    private static Float normalizeUnit(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 1f) {
            return null;
        }
        return value;
    }

    private static Float normalizePenalty(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 4f) {
            return null;
        }
        return value;
    }

    private static String normalizeLane(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return "TASK".equals(normalized) ? "TASK" : "CHAT";
    }

    private static Integer clampPriority(Integer value) {
        if (value == null) return MIN_TASK_PRIORITY;
        return Math.max(MIN_TASK_PRIORITY, Math.min(MAX_TASK_PRIORITY, value));
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

    public record InferencePolicyPayload(
            Boolean frameGuardEnabled,
            Integer targetFps,
            Boolean mtpEnabled
    ) implements ITianshuPayload {
        public InferencePolicyPayload {
            targetFps = targetFps == null ? null : Math.max(15, Math.min(240, targetFps));
        }

        public static InferencePolicyPayload followGlobal() {
            return new InferencePolicyPayload(null, null, null);
        }
    }
}
