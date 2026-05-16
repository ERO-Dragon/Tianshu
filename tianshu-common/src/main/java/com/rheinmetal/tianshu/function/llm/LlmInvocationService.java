package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.engine.LlmEngine;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine.ChatMessage;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine.FinishReason;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationError;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationFinishReason;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationHandle;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationLane;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationResult;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagEntry;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagHit;
import com.rheinmetal.tianshu.function.llm.inference.LlmStreamSink;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmInvocationService {
    private final LlmEngine llmEngine;
    private final LlmProtocolAdapter adapter;

    public LlmInvocationService(LlmEngine llmEngine, LlmProtocolAdapter adapter) {
        this.llmEngine = llmEngine;
        this.adapter = adapter;
    }

    public LlmInvocationHandle submitStreaming(LlmInvocationRequest request, LlmStreamSink sink) {
        LlmInvocationRequest effectiveRequest = request == null ? LlmInvocationRequest.streaming("llm.invocation", java.util.List.of()) : request;
        CompletableFuture<LlmInvocationResult> resultFuture = new CompletableFuture<>();
        AtomicReference<LlmInvocationError> lastError = new AtomicReference<>();
        AtomicBoolean terminalEmitted = new AtomicBoolean(false);
        StringBuilder collected = new StringBuilder();
        List<LlmRagHit> ragHits = new ArrayList<>();
        ProtocolTaskHandle taskHandle = adapter.submitLlmIoTask(effectiveRequest.requestKey(), () -> streamBlocking(effectiveRequest, new LlmStreamSink() {
            @Override
            public void onChunk(String text) {
                if (terminalEmitted.get()) {
                    return;
                }
                collected.append(text == null ? "" : text);
                if (sink != null) {
                    sink.onChunk(text);
                }
            }

            @Override
            public void onRagHit(LlmRagHit hit) {
                if (hit == null) {
                    return;
                }
                ragHits.add(hit);
                if (sink != null) {
                    sink.onRagHit(hit);
                }
            }

            @Override
            public void onFinish(LlmInvocationFinishReason finishReason) {
                completeInvocation(terminalEmitted, resultFuture, sink, finishReason, collected.toString(), lastError.get(), ragHits);
            }

            @Override
            public void onError(LlmInvocationError error) {
                lastError.set(error);
                completeInvocation(terminalEmitted, resultFuture, sink, LlmInvocationFinishReason.FAILED, collected.toString(), error, ragHits);
            }
        }));
        if (taskHandle != null && taskHandle.state() == ProtocolTaskState.REJECTED) {
            completeInvocation(terminalEmitted, resultFuture, sink, LlmInvocationFinishReason.FAILED, collected.toString(), new LlmInvocationError("LLM_TASK_REJECTED", "LLM invocation task was rejected", null), ragHits);
        }
        return new LlmInvocationHandle(effectiveRequest.requestKey(), taskHandle, resultFuture);
    }

    public LlmInvocationHandle submitCollecting(LlmInvocationRequest request) {
        LlmInvocationRequest effectiveRequest = request == null ? LlmInvocationRequest.collecting("llm.invocation", java.util.List.of()) : request;
        return submitStreaming(new LlmInvocationRequest(effectiveRequest.requestKey(), effectiveRequest.messages(), effectiveRequest.options().streaming(false), effectiveRequest.ragContext()), null);
    }

    public LlmInvocationHandle submitTask(LlmInvocationRequest request) {
        return submitTask(request, null);
    }

    public LlmInvocationHandle submitTask(LlmInvocationRequest request, LlmStreamSink sink) {
        LlmInvocationRequest effectiveRequest = request == null ? LlmInvocationRequest.task("llm.task", java.util.List.of()) : request;
        return submitStreaming(asTaskInvocationRequest(effectiveRequest), sink);
    }

    public void cancelActiveGeneration() {
        llmEngine.cancelGeneration();
    }

    private void streamBlocking(LlmInvocationRequest request, LlmStreamSink sink) {
        if (sink == null) {
            return;
        }
        LlmInvocationRequest effectiveRequest = request == null ? LlmInvocationRequest.streaming("llm.invocation", List.of()) : request;
        long requestId = llmEngine.beginStreamRequest(message -> sink.onError(new LlmInvocationError("LLM_INVOCATION_ERROR", message, null)));
        if (requestId <= 0L) {
            sink.onError(new LlmInvocationError("LLM_REQUEST_REJECTED", "LLM stream request rejected", null));
            sink.onFinish(LlmInvocationFinishReason.FAILED);
            return;
        }
        List<ChatMessage> messages = effectiveRequest.messages().stream()
                .map(message -> new ChatMessage(message.role().wireName(), message.content()))
                .toList();
        List<String> dynamicRag = effectiveRequest.ragContext().dynamicRag().stream()
                .map(LlmRagEntry::text)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        llmEngine.streamChatBlocking(
                requestId,
                messages,
                effectiveRequest.options().temperature(),
                effectiveRequest.options().stream(),
                effectiveRequest.options().thinking(),
                effectiveRequest.options().maxTokens(),
                effectiveRequest.options().lane(),
                effectiveRequest.options().useRag(),
                effectiveRequest.options().useMemoryRag(),
                effectiveRequest.options().memoryRagTokenBudget(),
                effectiveRequest.options().includeRagHits(),
                effectiveRequest.options().taskPriority(),
                effectiveRequest.options().taskPreemptible(),
                dynamicRag,
                effectiveRequest.ragContext().routing(),
                sink::onChunk,
                sink::onRagHit,
                finishReason -> sink.onFinish(convertFinishReason(finishReason)),
                message -> sink.onError(new LlmInvocationError("LLM_INVOCATION_ERROR", message, null))
        );
    }

    private LlmInvocationRequest asTaskInvocationRequest(LlmInvocationRequest request) {
        return new LlmInvocationRequest(request.requestKey(), request.messages(), request.options().lane(LlmInvocationLane.TASK), request.ragContext());
    }

    private LlmInvocationFinishReason convertFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return LlmInvocationFinishReason.FAILED;
        }
        return switch (finishReason) {
            case COMPLETED -> LlmInvocationFinishReason.COMPLETED;
            case CANCELLED -> LlmInvocationFinishReason.CANCELLED;
            case FAILED -> LlmInvocationFinishReason.FAILED;
        };
    }

    private void completeInvocation(
            AtomicBoolean terminalEmitted,
            CompletableFuture<LlmInvocationResult> resultFuture,
            LlmStreamSink sink,
            LlmInvocationFinishReason finishReason,
            String text,
            LlmInvocationError error,
            List<LlmRagHit> ragHits
    ) {
        if (!terminalEmitted.compareAndSet(false, true)) {
            return;
        }
        LlmInvocationResult result = toResult(finishReason, text, error, ragHits);
        resultFuture.complete(result);
        if (sink == null) {
            return;
        }
        if (result.finishReason() == LlmInvocationFinishReason.FAILED) {
            sink.onError(result.error());
        }
        sink.onFinish(result.finishReason());
    }

    private LlmInvocationResult toResult(LlmInvocationFinishReason finishReason, String text, LlmInvocationError error, List<LlmRagHit> ragHits) {
        if (finishReason == LlmInvocationFinishReason.COMPLETED) {
            return LlmInvocationResult.completed(text, ragHits);
        }
        if (finishReason == LlmInvocationFinishReason.CANCELLED) {
            return LlmInvocationResult.cancelled(text, ragHits);
        }
        LlmInvocationError effectiveError = error == null ? new LlmInvocationError("LLM_INVOCATION_FAILED", "LLM invocation failed", null) : error;
        return LlmInvocationResult.failed(effectiveError, text, ragHits);
    }
}
