package com.rheinmetal.tianshu.function.intake.audio;

import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Builds the microphone processing chain from the settings that actually exist. */
public final class AsrAudioPipelineFactory {
    private static final int ASR_SAMPLE_RATE = 16000;
    private static final double DEFAULT_HIGH_PASS_CUTOFF_HZ = 80.0D;

    private final IntakeConfiguration config;

    public AsrAudioPipelineFactory(IntakeConfiguration config) {
        this.config = config;
    }

    public AudioFrameProcessor create() {
        List<AudioFrameProcessor> processors = new ArrayList<>();
        if (config == null || config.isAsrHighPassFilterEnabled()) {
            processors.add(new HighPassFilterProcessor(ASR_SAMPLE_RATE, DEFAULT_HIGH_PASS_CUTOFF_HZ));
        }
        return AudioFrameProcessor.chain(processors);
    }
}