package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.service.Chunk;
import com.rheinmetal.tianshu.function.llm.service.LLMRequest;
import com.rheinmetal.tianshu.function.llm.service.LlmInferencePolicy;
import com.rheinmetal.tianshu.function.llm.service.MessageItem;
import com.rheinmetal.tianshu.protocol.payload.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;

final class LlmPromptPayloadMapper {
    LLMRequest toRequest(LLMPromptRequestPayload payload) {
        LLMRequest request = new LLMRequest();
        if (payload == null) {
            return request;
        }
        request.setMaxTokens(payload.maxTokens());
        request.setTemperature(payload.temperature());
        request.setTopK(payload.topK());
        request.setTopP(payload.topP());
        request.setMinP(payload.minP());
        request.setPenaltyRepeat(payload.penaltyRepeat());
        request.setPenaltyFreq(payload.penaltyFreq());
        request.setPenaltyPresent(payload.penaltyPresent());
        request.setPenaltyLastN(payload.penaltyLastN());
        request.setStream(payload.stream());
        request.setThinking(payload.thinking());
        request.setCaptureThinkingContent(payload.captureThinkingContent());
        request.setToolsJson(payload.toolsJson());
        request.setLane(payload.lane());
        request.setTaskPriority(payload.taskPriority());
        request.setTaskPreemptible(payload.taskPreemptible());
        request.setInferencePolicy(toInferencePolicy(payload.inferencePolicy()));
        for (LLMPromptRequestPayload.ChunkPayload chunk : payload.chunks()) {
            if (chunk == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.type())) {
                request.addChunk(toMessageChunk(chunk.messageContent()));
            } else if ("rag".equalsIgnoreCase(chunk.type())) {
                request.addChunk(toRagChunk(
                        chunk.uid(),
                        chunk.prompt(),
                        chunk.ragContent(),
                        chunk.useCache(),
                        chunk.includeRagHits(),
                        chunk.memoryRagTokenBudget()
                ));
            }
        }
        return request;
    }

    LLMRequest toTokenCountRequest(LLMPrimitiveQueryPayload payload) {
        LLMRequest request = new LLMRequest();
        if (payload == null) {
            return request;
        }
        if (payload.text() != null && !payload.text().isBlank()) {
            request.addChunk(Chunk.message(MessageItem.user(payload.text())));
        }
        if (!payload.messages().isEmpty()) {
            request.addChunk(Chunk.message(payload.messages().stream()
                    .map(message -> new MessageItem(message.role(), message.content()))
                    .toList()));
        }
        for (LLMPrimitiveQueryPayload.ChunkPayload chunk : payload.chunks()) {
            if (chunk == null) {
                continue;
            }
            if ("message".equalsIgnoreCase(chunk.type())) {
                request.addChunk(Chunk.message(chunk.messageContent().stream()
                        .map(message -> new MessageItem(message.role(), message.content()))
                        .toList()));
            } else if ("rag".equalsIgnoreCase(chunk.type())) {
                request.addChunk(toRagChunk(
                        chunk.uid(),
                        chunk.prompt(),
                        chunk.ragContent(),
                        chunk.useCache(),
                        chunk.includeRagHits(),
                        chunk.memoryRagTokenBudget()
                ));
            }
        }
        return request;
    }

    private LlmInferencePolicy toInferencePolicy(LLMPromptRequestPayload.InferencePolicyPayload payload) {
        return payload == null
                ? LlmInferencePolicy.defaults()
                : new LlmInferencePolicy(payload.frameGuardEnabled(), payload.targetFps(), payload.mtpEnabled());
    }

    private Chunk toMessageChunk(List<LLMPromptRequestPayload.MessageItemPayload> messages) {
        return Chunk.message(messages == null ? List.of() : messages.stream()
                .map(message -> new MessageItem(message.role(), message.content()))
                .toList());
    }

    private Chunk toRagChunk(
            String uid,
            String prompt,
            List<String> contents,
            Boolean useCache,
            Boolean includeRagHits,
            Integer tokenBudget
    ) {
        return Chunk.rag(
                uid,
                prompt,
                contents,
                Boolean.TRUE.equals(useCache),
                Boolean.TRUE.equals(includeRagHits),
                tokenBudget == null ? 1000 : tokenBudget
        );
    }
}
