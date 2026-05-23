package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmRuntimeSnapshot(
        boolean enabled,
        LlmRuntimeState state,
        boolean running,
        boolean healthy,
        String modelName,
        String failureMessage,
        long updatedAtMillis
) {
    public LlmRuntimeSnapshot {
        state = state == null ? LlmRuntimeState.STOPPED : state;
        modelName = modelName == null ? "" : modelName.trim();
        failureMessage = failureMessage == null ? "" : failureMessage.trim();
        updatedAtMillis = updatedAtMillis > 0L ? updatedAtMillis : System.currentTimeMillis();
    }

    public static LlmRuntimeSnapshot disabled() {
        return new LlmRuntimeSnapshot(false, LlmRuntimeState.DISABLED, false, false, "", "", System.currentTimeMillis());
    }
}
