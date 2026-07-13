package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.LlmModelManager;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;

import java.util.ArrayList;
import java.util.List;

final class LlmRequestPreparer {
    private final LlmInferenceClient inferenceClient;
    private final LlmInferenceGovernor inferenceGovernor;
    private final LlmRagService ragService;
    private final LlmServiceMetadata metadata;

    LlmRequestPreparer(
            LlmInferenceClient inferenceClient,
            LlmInferenceGovernor inferenceGovernor,
            LlmRagService ragService,
            LlmServiceMetadata metadata
    ) {
        this.inferenceClient = inferenceClient;
        this.inferenceGovernor = inferenceGovernor;
        this.ragService = ragService;
        this.metadata = metadata;
    }

    PreparedRequest prepare(LLMRequest request) {
        LLMRequest effectiveRequest = request == null ? new LLMRequest() : request;
        MessageAssembler messages = new MessageAssembler();
        List<LLMPromptResultPayload.RagHitPayload> ragHits = new ArrayList<>();
        String queryText = extractLastUserMessage(effectiveRequest);

        for (Chunk chunk : effectiveRequest.getChunks()) {
            if (chunk == null || chunk.getType() == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.getType())) {
                messages.appendMessages(chunk.getMessageContent());
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                LlmRagService.RagPreparation rag = ragService.prepareChunk(chunk, queryText);
                ragHits.addAll(ragService.hits(chunk, rag.results()));
                messages.appendSystemPart(rag.prompt());
            }
        }

        return new PreparedRequest(
                buildLibsMessages(messages.finish()),
                createSampler(effectiveRequest),
                maxTokens(effectiveRequest),
                effectiveRequest.getTaskPriority(),
                effectiveRequest.getTaskPreemptible(),
                inferenceGovernor.resolve(
                                effectiveRequest.getInferencePolicy(),
                                effectiveRequest.isTaskLane(),
                                inferenceClient.supportsMtp()
                        )
                        .withRequestOptions(
                                Boolean.TRUE.equals(effectiveRequest.getCaptureThinkingContent()),
                                effectiveRequest.getToolsJson()
                        ),
                ragHits
        );
    }

    PreparedRequest prepareTokenCount(LLMRequest request) {
        LLMRequest effectiveRequest = request == null ? new LLMRequest() : request;
        MessageAssembler messages = new MessageAssembler();
        for (Chunk chunk : effectiveRequest.getChunks()) {
            if (chunk == null || chunk.getType() == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.getType())) {
                messages.appendMessages(chunk.getMessageContent());
            } else if ("rag".equalsIgnoreCase(chunk.getType())) {
                throw new UnsupportedOperationException(
                        "TOKEN_COUNT only accepts message-only input; rag chunks may trigger retrieval or cache mutation"
                );
            }
        }
        return new PreparedRequest(
                buildLibsMessages(messages.finish()),
                createSampler(effectiveRequest),
                maxTokens(effectiveRequest),
                effectiveRequest.getTaskPriority(),
                effectiveRequest.getTaskPreemptible(),
                LlmInferenceOptions.defaults(),
                List.of()
        );
    }

    private String extractLastUserMessage(LLMRequest request) {
        for (int chunkIndex = request.getChunks().size() - 1; chunkIndex >= 0; chunkIndex--) {
            Chunk chunk = request.getChunks().get(chunkIndex);
            if (chunk == null || !"message".equalsIgnoreCase(chunk.getType()) || chunk.getMessageContent() == null) {
                continue;
            }
            for (int messageIndex = chunk.getMessageContent().size() - 1; messageIndex >= 0; messageIndex--) {
                MessageItem message = chunk.getMessageContent().get(messageIndex);
                if (message != null
                        && "user".equalsIgnoreCase(message.getRole())
                        && message.getContent() != null
                        && !message.getContent().isBlank()) {
                    return message.getContent();
                }
            }
        }
        return null;
    }

    private List<ChatMessage> buildLibsMessages(List<MessageItem> messages) {
        return messages.stream()
                .map(message -> new ChatMessage(message.getRole(), message.getContent()))
                .toList();
    }

    private SamplerConfig createSampler(LLMRequest request) {
        SamplerConfig sampler = applyModelSamplingDefaults(
                SamplerConfig.defaults(),
                Boolean.TRUE.equals(request.getThinking())
        );
        if (request.getTemperature() != null) sampler.setTemperature(request.getTemperature());
        if (request.getTopK() != null) sampler.setTopK(request.getTopK());
        if (request.getTopP() != null) sampler.setTopP(request.getTopP());
        if (request.getMinP() != null) sampler.setMinP(request.getMinP());
        if (request.getPenaltyRepeat() != null) sampler.setPenaltyRepeat(request.getPenaltyRepeat());
        if (request.getPenaltyFreq() != null) sampler.setPenaltyFreq(request.getPenaltyFreq());
        if (request.getPenaltyPresent() != null) sampler.setPenaltyPresent(request.getPenaltyPresent());
        if (request.getPenaltyLastN() != null) sampler.setPenaltyLastN(request.getPenaltyLastN());
        sampler.setEnableThinking(Boolean.TRUE.equals(request.getThinking()));
        return sampler;
    }

    private SamplerConfig applyModelSamplingDefaults(SamplerConfig sampler, boolean thinking) {
        LlmModelInfo info = LlmModelManager.getModelByName(metadata.modelName());
        LlmModelInfo.SamplingSettings settings = info == null ? null : info.getSamplingSettings(thinking);
        if (settings == null || settings.isEmpty()) {
            return sampler;
        }
        if (settings.temperature != null) sampler.setTemperature(settings.temperature);
        if (settings.topK != null) sampler.setTopK(settings.topK);
        if (settings.topP != null) sampler.setTopP(settings.topP);
        if (settings.minP != null) sampler.setMinP(settings.minP);
        if (settings.penaltyRepeat != null) sampler.setPenaltyRepeat(settings.penaltyRepeat);
        if (settings.penaltyFreq != null) sampler.setPenaltyFreq(settings.penaltyFreq);
        if (settings.penaltyPresent != null) sampler.setPenaltyPresent(settings.penaltyPresent);
        if (settings.penaltyLastN != null) sampler.setPenaltyLastN(settings.penaltyLastN);
        return sampler;
    }

    private int maxTokens(LLMRequest request) {
        Integer maxTokens = request.getMaxTokens();
        return maxTokens != null && maxTokens > 0 ? maxTokens : 0;
    }

    record PreparedRequest(
            List<ChatMessage> messages,
            SamplerConfig sampler,
            int maxTokens,
            int taskPriority,
            boolean taskPreemptible,
            LlmInferenceOptions options,
            List<LLMPromptResultPayload.RagHitPayload> ragHits
    ) {
        PreparedRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            options = options == null ? LlmInferenceOptions.defaults() : options;
            ragHits = ragHits == null ? List.of() : List.copyOf(ragHits);
        }
    }

    private static final class MessageAssembler {
        private final StringBuilder leadingSystem = new StringBuilder();
        private final List<MessageItem> messages = new ArrayList<>();
        private boolean dialogueStarted;

        void appendMessages(List<MessageItem> items) {
            if (items != null) {
                items.forEach(this::appendMessage);
            }
        }

        void appendSystemPart(String content) {
            if (content == null || content.isBlank()) {
                return;
            }
            if (leadingSystem.length() > 0) {
                leadingSystem.append('\n');
            }
            leadingSystem.append(content.trim());
        }

        List<MessageItem> finish() {
            List<MessageItem> result = new ArrayList<>();
            if (leadingSystem.length() > 0) {
                result.add(MessageItem.system(leadingSystem.toString()));
            }
            result.addAll(messages);
            return result;
        }

        private void appendMessage(MessageItem message) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                return;
            }
            String role = normalizeRole(message.getRole());
            if ("system".equals(role)) {
                if (dialogueStarted) {
                    throw new IllegalArgumentException("LLM_UNSUPPORTED_SYSTEM_POSITION");
                }
                appendSystemPart(message.getContent());
                return;
            }
            dialogueStarted = true;
            messages.add(new MessageItem(role, message.getContent()));
        }

        private static String normalizeRole(String role) {
            if (role == null) {
                return "user";
            }
            return switch (role.trim().toLowerCase()) {
                case "system", "assistant", "user" -> role.trim().toLowerCase();
                default -> "user";
            };
        }
    }
}
