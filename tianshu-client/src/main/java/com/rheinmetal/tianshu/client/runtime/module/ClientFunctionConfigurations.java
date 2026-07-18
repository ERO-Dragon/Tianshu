package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;

import java.util.Objects;

public record ClientFunctionConfigurations(
        AsrConfiguration asr,
        LlmConfiguration llm,
        TtsConfiguration tts,
        AXStorageConfiguration ax
) {
    public ClientFunctionConfigurations {
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(tts, "tts");
        Objects.requireNonNull(ax, "ax");
    }
}
