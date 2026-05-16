package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.AssistantLlmClient;
import com.rheinmetal.tianshu.function.assistant.AssistantLlmRequestHandler;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptResourceRepository;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScopeProvider;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmTaskUsageKind;
import com.rheinmetal.tianshu.protocol.payload.LlmUsageAuthorizationPayload;

import java.util.List;

public final class AssistantCompressionTaskDispatcher {
    private static final String PURPOSE_SHORT_TERM = "assistant.memory.short_term_compression";
    private final AssistantMemorySystem memorySystem;
    private final AssistantLlmClient llmClient;
    private final AssistantScopeProvider scopeProvider;
    private final AssistantCompressionTaskResultHandler resultHandler;
    private final AssistantCompressionPromptProvider promptProvider;

    public AssistantCompressionTaskDispatcher(AssistantMemorySystem memorySystem, AssistantLlmClient llmClient, AssistantScopeProvider scopeProvider) {
        this(memorySystem, llmClient, scopeProvider, null);
    }

    public AssistantCompressionTaskDispatcher(AssistantMemorySystem memorySystem, AssistantLlmClient llmClient, AssistantScopeProvider scopeProvider, AssistantPromptResourceRepository promptResourceRepository) {
        this.memorySystem = memorySystem;
        this.llmClient = llmClient;
        this.scopeProvider = scopeProvider;
        this.resultHandler = new AssistantCompressionTaskResultHandler(memorySystem);
        this.promptProvider = new AssistantCompressionPromptProvider(promptResourceRepository);
    }

    public boolean dispatchNext(AssistantScope scope) {
        if (memorySystem == null || llmClient == null || scope == null || !scope.writable()) {
            return false;
        }
        AssistantCompressionTask task = memorySystem.planNextCompressionTask(scope);
        if (task.isEmpty() || task.state() == AssistantCompressionTaskState.SUBMITTED) {
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

    private LlmTaskRequestPayload toTaskRequest(AssistantCompressionTask task) {
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
                "tianshu_assistant",
                "none",
                List.of(),
                LlmUsageAuthorizationPayload.EMPTY
        );
    }

    private List<LlmInvocationMessage> messages(AssistantCompressionTask task) {
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

    private final class CompressionResultHandler implements AssistantLlmRequestHandler {
        private final AssistantScope scope;
        private final AssistantCompressionTask task;

        private CompressionResultHandler(AssistantScope scope, AssistantCompressionTask task) {
            this.scope = scope;
            this.task = task;
        }

        @Override
        public void onResult(LlmTaskResultPayload payload) {
            AssistantScope effectiveScope = scope == null ? scopeProvider.currentScope() : scope;
            AssistantCompressionTask effectiveTask = memorySystem.findCompressionTask(effectiveScope, task.taskId());
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
