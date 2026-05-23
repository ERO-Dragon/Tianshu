package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.AXLlmClient;
import com.rheinmetal.tianshu.function.auxilium.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeProvider;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskUsageKind;
import com.rheinmetal.tianshu.protocol.payload.LlmUsageAuthorizationPayload;

import java.util.List;

public final class AXCompressionTaskDispatcher {
    private static final String PURPOSE_SHORT_TERM = "AX.memory.short_term_compression";
    private final AXMemorySystem memorySystem;
    private final AXLlmClient llmClient;
    private final AXScopeProvider scopeProvider;
    private final AXCompressionTaskResultHandler resultHandler;
    private final AXCompressionPromptProvider promptProvider;

    public AXCompressionTaskDispatcher(AXMemorySystem memorySystem, AXLlmClient llmClient, AXScopeProvider scopeProvider) {
        this(memorySystem, llmClient, scopeProvider, null);
    }

    public AXCompressionTaskDispatcher(AXMemorySystem memorySystem, AXLlmClient llmClient, AXScopeProvider scopeProvider, AXPromptResourceRepository promptResourceRepository) {
        this(memorySystem, llmClient, scopeProvider, promptResourceRepository, null);
    }

    public AXCompressionTaskDispatcher(AXMemorySystem memorySystem, AXLlmClient llmClient, AXScopeProvider scopeProvider, AXPromptResourceRepository promptResourceRepository, AXPromptLanguageProvider languageProvider) {
        this.memorySystem = memorySystem;
        this.llmClient = llmClient;
        this.scopeProvider = scopeProvider;
        this.resultHandler = new AXCompressionTaskResultHandler(memorySystem);
        this.promptProvider = new AXCompressionPromptProvider(promptResourceRepository, languageProvider);
    }

    public boolean dispatchNext(AXScope scope) {
        if (memorySystem == null || llmClient == null || scope == null || !scope.writable()) {
            return false;
        }
        AXCompressionTask task = memorySystem.planNextCompressionTask(scope);
        if (task.isEmpty() || task.state() == AXCompressionTaskState.SUBMITTED) {
            return false;
        }
        try {
            llmClient.submitDetached(toTaskRequest(task), new CompressionResultHandler(scope, task));
            memorySystem.markCompressionTaskSubmitted(scope, task);
            return true;
        } catch (RuntimeException ex) {
            memorySystem.suspendCompressionTask(scope, task, "TASK_GATEWAY_REJECTED", "LLM_PROTOCOL_REJECTED");
            return false;
        }
    }

    private LlmTaskRequestPayload toTaskRequest(AXCompressionTask task) {
        long now = System.currentTimeMillis();
        return new LlmTaskRequestPayload(
                task.taskId(),
                PURPOSE_SHORT_TERM,
                LlmTaskUsageKind.TASK,
                messages(task).stream().map(this::toPayload).toList(),
                List.of(),
                0,
                true,
                false,
                true,
                false,
                800,
                0.2D,
                now + 300000L,
                "",
                "tianshu_AX",
                "none",
                List.of(),
                LlmUsageAuthorizationPayload.EMPTY
        );
    }

    private List<LlmInvocationMessage> messages(AXCompressionTask task) {
        return List.of(
                LlmInvocationMessage.system(promptProvider.promptFor(task.type())),
                LlmInvocationMessage.user(sourceText(task.sourceTurns()))
        );
    }

    private LlmTaskMessagePayload toPayload(LlmInvocationMessage message) {
        return new LlmTaskMessagePayload(message.role().wireName(), message.content());
    }

    private String sourceText(List<ConversationTurn> turns) {
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            if (turn == null || turn.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(turn.role()).append(": ").append(turn.content());
        }
        return builder.toString();
    }

    private final class CompressionResultHandler implements AXLlmRequestHandler {
        private final AXScope scope;
        private final AXCompressionTask task;

        private CompressionResultHandler(AXScope scope, AXCompressionTask task) {
            this.scope = scope;
            this.task = task;
        }

        @Override
        public void onResult(LlmTaskResultPayload payload) {
            AXScope effectiveScope = scope == null ? scopeProvider.currentScope() : scope;
            AXCompressionTask effectiveTask = memorySystem.findCompressionTask(effectiveScope, task.taskId());
            if (effectiveTask.isEmpty()) {
                return;
            }
            if (payload != null && PURPOSE_SHORT_TERM.equals(payload.purpose()) && "COMPLETED".equals(payload.status())) {
                resultHandler.acceptResult(effectiveScope, effectiveTask, payload.text());
                return;
            }
            String errorCode = payload == null ? "LLM_TASK_FAILED" : payload.errorCode();
            String errorMessage = payload == null ? "LLM task failed" : payload.errorMessage();
            memorySystem.failCompressionTask(effectiveScope, effectiveTask, errorCode, errorMessage);
        }
    }
}
