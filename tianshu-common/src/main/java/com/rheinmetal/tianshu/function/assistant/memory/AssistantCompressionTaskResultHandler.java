package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

public final class AssistantCompressionTaskResultHandler {
    private final AssistantMemorySystem memorySystem;

    public AssistantCompressionTaskResultHandler(AssistantMemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    public void acceptResult(AssistantScope scope, AssistantCompressionTask task, String resultText) {
        if (task == null || task.isEmpty()) {
            return;
        }
        if (task.type() == AssistantCompressionTaskType.LONG_TERM_MEMORY) {
            acceptLongTermResult(scope, task, resultText);
            return;
        }
        acceptShortTermResult(scope, task, resultText);
    }

    public void acceptShortTermResult(AssistantScope scope, AssistantCompressionTask task, String resultText) {
        if (memorySystem == null || scope == null || !scope.writable() || task == null || task.isEmpty()) {
            return;
        }
        String normalized = resultText == null ? "" : resultText.trim();
        if (normalized.isBlank()) {
            memorySystem.suspendCompressionTask(scope, task);
            return;
        }
        ShortTermMemoryBlock block = new ShortTermMemoryBlock(
                task.taskId(),
                System.currentTimeMillis(),
                task.sourceTurns().isEmpty() ? 0L : task.sourceTurns().get(0).createdAt(),
                task.sourceTurns().isEmpty() ? 0L : task.sourceTurns().get(task.sourceTurns().size() - 1).createdAt(),
                task.sourceTurnCount(),
                task.estimatedTokens(),
                normalized
        );
        memorySystem.acceptShortTermCompression(scope, block, task.sourceTurns().size());
    }

    private void acceptLongTermResult(AssistantScope scope, AssistantCompressionTask task, String resultText) {
        if (memorySystem == null || scope == null || !scope.writable()) {
            return;
        }
        String normalized = resultText == null ? "" : resultText.trim();
        if (normalized.isBlank()) {
            memorySystem.suspendCompressionTask(scope, task);
            return;
        }
        memorySystem.acceptLongTermCompression(scope, task, normalized);
    }
}
