package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.function.intake.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.echo.settings.TtsConfiguration;

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
