package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;

public final class LlmRuntimeCapabilities {
    public static final RuntimeCapability INFERENCE = RuntimeCapability.of("capability.llm.inference");
    public static final RuntimeCapability TASK = RuntimeCapability.of("capability.llm.task");

    private LlmRuntimeCapabilities() {
    }
}
