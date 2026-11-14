package com.rheinmetal.tianshu.function.weaving.protocol;

/**
 * Stable identity of the cloud-LLM module.
 *
 * <p>Kept beside the capability names rather than on the protocol adapter so services that only need
 * to tag a diagnostic do not have to depend on the adapter.</p>
 */
public final class LlmModuleId {

    public static final String MODULE = "module.llm";

    private LlmModuleId() {
    }
}
