package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;

public final class LlmRuntimeCapabilities {
    public static final RuntimeCapability LLM_REQUEST = RuntimeCapability.of("capability.llm.request");
    public static final RuntimeCapability LLM_CACHE_MANAGE = RuntimeCapability.of("capability.llm.cache_manage");

    private LlmRuntimeCapabilities() {
    }
}
