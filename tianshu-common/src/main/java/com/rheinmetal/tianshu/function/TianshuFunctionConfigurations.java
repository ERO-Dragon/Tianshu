package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;

import java.util.Objects;

public record TianshuFunctionConfigurations(
        AsrConfiguration asr,
        LlmConfiguration llm,
        TtsConfiguration tts,
        AXStorageConfiguration ax
) {
    public TianshuFunctionConfigurations {
        Objects.requireNonNull(asr, "asr");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(tts, "tts");
        Objects.requireNonNull(ax, "ax");
    }
}
