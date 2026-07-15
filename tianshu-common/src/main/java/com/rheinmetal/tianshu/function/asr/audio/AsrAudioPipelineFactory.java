package com.rheinmetal.tianshu.function.asr.audio;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class AsrAudioPipelineFactory {
    private static final int ASR_SAMPLE_RATE = 16000;
    private static final double DEFAULT_HIGH_PASS_CUTOFF_HZ = 80.0D;

    private final AsrConfiguration config;
    private final IGameEnvironment env;
    private final NoiseSuppressorProvider noiseSuppressorProvider;

    public AsrAudioPipelineFactory(AsrConfiguration config, IGameEnvironment env) {
        this(config, env, NoiseSuppressorProvider.unavailable());
    }

    public AsrAudioPipelineFactory(AsrConfiguration config, IGameEnvironment env, NoiseSuppressorProvider noiseSuppressorProvider) {
        this.config = config;
        this.env = env;
        this.noiseSuppressorProvider = noiseSuppressorProvider == null ? NoiseSuppressorProvider.unavailable() : noiseSuppressorProvider;
    }

    public AudioFrameProcessor create() {
        List<AudioFrameProcessor> processors = new ArrayList<>();
        if (config == null || config.isAsrHighPassFilterEnabled()) {
            processors.add(new HighPassFilterProcessor(ASR_SAMPLE_RATE, DEFAULT_HIGH_PASS_CUTOFF_HZ));
        }
        if (config != null && config.isAsrRnnoiseEnabled()) {
            AudioFrameProcessor noiseSuppressor = noiseSuppressorProvider.create();
            if (noiseSuppressor != null) {
                processors.add(noiseSuppressor);
            } else if (env != null) {
                env.warn("ASR RNNoise is enabled but no noise suppressor backend is available");
            }
        }
        return AudioFrameProcessor.chain(processors);
    }
}
