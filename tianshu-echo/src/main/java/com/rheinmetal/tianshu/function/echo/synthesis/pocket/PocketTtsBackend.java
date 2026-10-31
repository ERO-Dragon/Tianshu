package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRuntimeFailurePolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsBackend;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsPcm16AudioConverter;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsResolvedModel;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pocket-TTS-ZH-4L backend. A synthesis request is a complete waveform for now; the same service
 * also exposes the pieces needed to stream later without rebuilding the model assembly.
 */
public final class PocketTtsBackend implements TtsBackend {
    private static final List<String> REQUIRED_FILES = List.of(
            "conditioner.onnx",
            "flowlm_prefill.onnx",
            "flowlm_step.onnx",
            "flowlm_shared.data",
            "mimi_encode.onnx",
            "mimi_decode_int8.onnx",
            "ort_glue.npz",
            "tokenizer.model"
    );

    private final IGameEnvironment env;
    private PocketTtsService service;
    private boolean initialized;
    private volatile boolean interrupted;
    private int sampleRate;

    public PocketTtsBackend(IGameEnvironment env) {
        this.env = env;
    }

    @Override
    public synchronized boolean initialize(TtsResolvedModel model) {
        shutdown();
        try {
            Path modelDir = model.modelDir();
            for (String required : REQUIRED_FILES) {
                if (!Files.isRegularFile(modelDir.resolve(required))) {
                    throw new IllegalStateException("POCKET_TTS_MODEL_INCOMPLETE: " + modelDir.resolve(required));
                }
            }
            service = new PocketTtsService(env, modelDir, InferenceResourcePolicy.systemDefault());
            sampleRate = service.sampleRate();
            initialized = true;
            env.info("Pocket-TTS backend initialized, sampleRate=" + sampleRate + "Hz");
            return true;
        } catch (Throwable failure) {
            TtsRuntimeFailurePolicy.rethrowFatal(failure);
            env.error("Pocket-TTS backend initialization failed", failure);
            shutdown();
            return false;
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized && service != null;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public synchronized boolean preloadVoice(TtsVoiceProfile voiceProfile) {
        if (!isInitialized() || voiceProfile == null) {
            return false;
        }
        boolean hasPath = voiceProfile.voiceSample() != null && !voiceProfile.voiceSample().isBlank()
                && Files.isRegularFile(Path.of(voiceProfile.voiceSample()));
        return hasPath || voiceProfile.referenceAudio().length > 0;
    }

    @Override
    public void synthesize(TtsRequest request, TtsAudioSink sink) {
        PocketTtsService current = service;
        if (!initialized || current == null) {
            throw new IllegalStateException("Pocket-TTS backend is not initialized");
        }
        interrupted = false;
        long startedNanos = System.nanoTime();
        TtsVoiceProfile voice = request.voiceProfile();
        try {
            float[][] audio;
            if (voice.voiceSample() != null && !voice.voiceSample().isBlank()) {
                audio = current.synthesizeToWaveform(request.text(), Path.of(voice.voiceSample()), this::isInterrupted);
            } else if (voice.referenceAudio().length > 0) {
                audio = current.synthesizeToWaveform(
                        request.text(), voice.referenceAudio(), voice.referenceSampleRate(), this::isInterrupted);
            } else {
                throw new IllegalStateException("Pocket-TTS requires a reference voice sample");
            }
            if (interrupted) {
                return;
            }
            byte[] pcm = TtsPcm16AudioConverter.fromChannels(audio);
            if (pcm.length > 0) {
                sink.accept(pcm);
            }
            sink.reportSynthesisMetrics(new TtsSynthesisMetrics(
                    TtsSynthesisMode.FULL,
                    request.text().length(),
                    pcmMillis(pcm),
                    elapsedMillis(startedNanos),
                    elapsedMillis(startedNanos)
            ));
        } catch (Throwable failure) {
            TtsRuntimeFailurePolicy.rethrowFatal(failure);
            env.error("Pocket-TTS synthesis failed requestId=" + request.requestId(), failure);
            throw new IllegalStateException("Pocket-TTS synthesis failed", failure);
        }
    }

    private long pcmMillis(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) {
            return 0L;
        }
        return (pcm.length / 2L) * 1000L / sampleRate;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    @Override
    public void interrupt() {
        interrupted = true;
    }

    private boolean isInterrupted() {
        return interrupted;
    }

    @Override
    public synchronized void shutdown() {
        interrupted = true;
        PocketTtsService current = service;
        service = null;
        initialized = false;
        sampleRate = 0;
        if (current != null) {
            current.close();
        }
    }
}
