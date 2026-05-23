package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

public final class AXCompressionTaskResultHandler {
    private final AXMemorySystem memorySystem;

    public AXCompressionTaskResultHandler(AXMemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    public void acceptResult(AXScope scope, AXCompressionTask task, String resultText) {
        if (task == null || task.isEmpty()) {
            return;
        }
        if (task.type() == AXCompressionTaskType.LONG_TERM_MEMORY) {
            acceptLongTermResult(scope, task, resultText);
            return;
        }
        acceptShortTermResult(scope, task, resultText);
    }

    public void acceptShortTermResult(AXScope scope, AXCompressionTask task, String resultText) {
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

    private void acceptLongTermResult(AXScope scope, AXCompressionTask task, String resultText) {
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
