package com.rheinmetal.tianshu.function.weaving.protocol;

/**
 * The topics the cloud-LLM module produces.
 *
 * <p>Owned here rather than in the kernel: a feature's topic name changes with the feature, not with the host.</p>
 */
public final class LlmTopics {

    public static final String LLM_STATUS = "LLM.STATUS";

    private LlmTopics() {
    }
}
