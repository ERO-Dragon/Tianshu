package com.rheinmetal.tianshu.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InferenceResourcePolicyTest {
    @Test
    void streamingAsrUsesSmallLowLatencyBudget() {
        assertEquals(1, InferenceResourcePolicy.fixedProcessors(2).sherpaAsrThreads(true, "transducer", "small"));
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(8).sherpaAsrThreads(true, "transducer", "small"));
    }

    @Test
    void heavierOfflineAsrCanUseUpToFourThreads() {
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(4).sherpaAsrThreads(false, "whisper", "large"));
        assertEquals(4, InferenceResourcePolicy.fixedProcessors(16).sherpaAsrThreads(false, "whisper", "large"));
    }

    @Test
    void ttsBudgetsStayInSmallOnnxRange() {
        InferenceResourcePolicy policy = InferenceResourcePolicy.fixedProcessors(16);

        assertEquals(4, policy.sherpaTtsThreads("vits", false));
        assertEquals(3, policy.sherpaTtsThreads("zipvoice", true));
        assertEquals(2, policy.mossTtsThreads());
    }

    @Test
    void autoregressiveMossUsesSmallModelThreadBudget() {
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(4).mossTtsThreads());
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(8).mossTtsThreads());
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(12).mossTtsThreads());
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(16).mossTtsThreads());
    }

    @Test
    void llmPolicyUsesSmallCpuHelperBudget() {
        assertEquals(2, InferenceResourcePolicy.fixedProcessors(2).embeddingGpuHelperThreads());
        assertEquals(3, InferenceResourcePolicy.fixedProcessors(8).embeddingGpuHelperThreads());
        assertEquals(4, InferenceResourcePolicy.fixedProcessors(16).embeddingGpuHelperThreads());
    }
}
