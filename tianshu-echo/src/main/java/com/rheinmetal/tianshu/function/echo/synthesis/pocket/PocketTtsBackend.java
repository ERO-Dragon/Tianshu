package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRuntimeFailurePolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsBackend;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsPcm16AudioConverter;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsResolvedModel;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pocket-TTS-ZH-4L backend. Complete-result delivery synthesizes one waveform; streaming delivery
 * overlaps FlowLM generation with Mimi decode on the shared codec lane.
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
    private final TtsCodecExecution codecExecution;
    private PocketTtsService service;
    private boolean initialized;
    private volatile boolean interrupted;
    private int sampleRate;
    private VoiceKey cachedVoiceKey;
    private float[][] cachedVoiceConditioning;

    public PocketTtsBackend(IGameEnvironment env, TtsCodecExecution codecExecution) {
        this.env = env;
        this.codecExecution = codecExecution;
    }

    @Override
    public synchronized boolean initialize(TtsResolvedModel model) {
        shutdown();
        boolean ready = false;
        try {
            Path modelDir = model.modelDir();
            for (String required : REQUIRED_FILES) {
                if (!Files.isRegularFile(modelDir.resolve(required))) {
                    throw new IllegalStateException("POCKET_TTS_MODEL_INCOMPLETE: " + modelDir.resolve(required));
                }
            }
            service = new PocketTtsService(env, modelDir, InferenceResourcePolicy.systemDefault(), codecExecution);
            sampleRate = service.sampleRate();
            initialized = true;
            env.info("Pocket-TTS backend initialized, sampleRate=" + sampleRate + "Hz");
            ready = true;
            return true;
        } catch (Throwable failure) {
            TtsRuntimeFailurePolicy.rethrowFatal(failure);
            env.error("Pocket-TTS backend initialization failed", failure);
            return false;
        } finally {
            // rethrowFatal leaves this method with a fatal Throwable before the catch body can clean
            // up, so the release has to sit in a finally; it is also the single owner for the plain
            // "initialize returned false" outcome.
            if (!ready) {
                shutdown();
            }
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
        try {
            voiceConditioning(service, voiceProfile);
            return true;
        } catch (Throwable failure) {
            TtsRuntimeFailurePolicy.rethrowFatal(failure);
            env.error("Pocket-TTS voice preload failed", failure);
            return false;
        }
    }

    @Override
    public void synthesize(TtsRequest request, TtsAudioSink sink) {
        PocketTtsService current = service;
        if (!initialized || current == null) {
            throw new IllegalStateException("Pocket-TTS backend is not initialized");
        }
        interrupted = false;
        TtsVoiceProfile voice = request.voiceProfile();
        try {
            if (sink.preferredSynthesisMode() == TtsSynthesisMode.STREAMING && codecExecution != null) {
                synthesizeStreaming(current, request, voice, sink);
            } else {
                synthesizeFull(current, request, voice, sink);
            }
        } catch (Throwable failure) {
            TtsRuntimeFailurePolicy.rethrowFatal(failure);
            env.error("Pocket-TTS synthesis failed requestId=" + request.requestId(), failure);
            throw new IllegalStateException("Pocket-TTS synthesis failed", failure);
        }
    }

    private void synthesizeFull(
            PocketTtsService current,
            TtsRequest request,
            TtsVoiceProfile voice,
            TtsAudioSink sink
    ) throws Exception {
        long startedNanos = System.nanoTime();
        float[][] audio = current.synthesizeToWaveform(
                request.text(), voiceConditioning(current, voice), this::isInterrupted);
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
    }

    private void synthesizeStreaming(
            PocketTtsService current,
            TtsRequest request,
            TtsVoiceProfile voice,
            TtsAudioSink sink
    ) throws Exception {
        long startedNanos = System.nanoTime();
        long[] firstAudioMillis = {0L};
        long[] audioMillis = {0L};
        Consumer<float[]> chunkSink = chunk -> {
            if (interrupted) {
                return;
            }
            byte[] pcm = TtsPcm16AudioConverter.fromChannels(new float[][]{chunk});
            if (pcm.length == 0) {
                return;
            }
            if (firstAudioMillis[0] == 0L) {
                firstAudioMillis[0] = elapsedMillis(startedNanos);
            }
            audioMillis[0] += pcmMillis(pcm);
            sink.accept(pcm);
        };
        current.synthesizeStreaming(request.text(), voiceConditioning(current, voice), chunkSink, this::isInterrupted);
        if (interrupted) {
            return;
        }
        sink.reportSynthesisMetrics(new TtsSynthesisMetrics(
                TtsSynthesisMode.STREAMING,
                request.text().length(),
                audioMillis[0],
                elapsedMillis(startedNanos),
                firstAudioMillis[0]
        ));
    }

    private synchronized float[][] voiceConditioning(PocketTtsService current, TtsVoiceProfile voice) throws Exception {
        if (voice.voiceSample() != null && !voice.voiceSample().isBlank()) {
            Path path = Path.of(voice.voiceSample()).toAbsolutePath().normalize();
            VoiceKey key = new VoiceKey(path.toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis(), 0);
            if (key.equals(cachedVoiceKey) && cachedVoiceConditioning != null) {
                return cachedVoiceConditioning;
            }
            float[][] conditioning = current.prepareVoice(path);
            cachedVoiceKey = key;
            cachedVoiceConditioning = conditioning;
            return conditioning;
        }
        if (voice.referenceAudio().length > 0) {
            int hash = Arrays.hashCode(voice.referenceAudio()) * 31 + voice.referenceSampleRate();
            VoiceKey key = new VoiceKey("", 0L, 0L, hash);
            if (key.equals(cachedVoiceKey) && cachedVoiceConditioning != null) {
                return cachedVoiceConditioning;
            }
            float[][] conditioning = current.prepareVoice(voice.referenceAudio(), voice.referenceSampleRate());
            cachedVoiceKey = key;
            cachedVoiceConditioning = conditioning;
            return conditioning;
        }
        throw new IllegalStateException("Pocket-TTS requires a reference voice sample");
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
        cachedVoiceKey = null;
        cachedVoiceConditioning = null;
        if (current != null) {
            current.close();
        }
    }

    private record VoiceKey(String path, long size, long modified, int referenceHash) {
    }
}
