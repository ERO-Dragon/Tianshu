package com.rheinmetal.tianshu.function.echo.synthesis;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRuntimeFailurePolicy;
import com.rheinmetal.tianshu.function.echo.model.TtsModelSettings;

public final class SherpaOnnxTtsBackend implements LocalTtsBackend {
    private final IGameEnvironment env;
    private final SherpaOnnxTtsConfigFactory configFactory;
    private OfflineTts tts;
    private volatile boolean initialized;
    private volatile boolean interrupted;
    private int sampleRate;
    private float defaultSpeed = 1.0f;
    private int defaultSpeakerId;

    public SherpaOnnxTtsBackend(IGameEnvironment env) {
        this.env = env;
        this.configFactory = new SherpaOnnxTtsConfigFactory(env);
    }

    @Override
    public synchronized boolean initialize(TtsResolvedModel model) {
        shutdown();
        boolean ready = false;
        try {
            SherpaOnnxTtsConfigFactory.ResolvedConfig resolvedConfig = configFactory.build(model).orElse(null);
            if (resolvedConfig == null) {
                return false;
            }
            tts = new OfflineTts(resolvedConfig.config());
            sampleRate = tts.getSampleRate();
            TtsModelSettings settings = TtsModelSettings.load(model.modelDir(), model.modelInfo());
            defaultSpeed = (float) settings.speed;
            defaultSpeakerId = settings.speakerId;
            initialized = true;
            env.info("Sherpa ONNX TTS backend initialized, sampleRate=" + sampleRate + "Hz, speakers=" + tts.getNumSpeakers());
            ready = true;
            return true;
        } catch (Throwable t) {
            TtsRuntimeFailurePolicy.rethrowFatal(t);
            env.error("Sherpa ONNX TTS backend initialization failed", t);
            return false;
        } finally {
            // rethrowFatal leaves this method with a fatal Throwable before the catch body can clean
            // up, so the release has to sit in a finally; it is also the single owner for the plain
            // "initialize returned false" outcomes.
            if (!ready) {
                shutdown();
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized && tts != null;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public void synthesize(TtsRequest request, TtsAudioSink sink) {
        OfflineTts current = tts;
        if (!initialized || current == null) {
            throw new IllegalStateException("Sherpa ONNX TTS backend is not initialized");
        }
        interrupted = false;
        float speed = resolveSpeed(request);
        int speakerId = resolveSpeakerId(request);
        env.info("Sherpa ONNX TTS synthesis started: " + request.text() + " (speed=" + speed + ", speaker=" + speakerId + ")");
        try {
            current.generateWithCallback(request.text(), speakerId, speed, samples -> {
                if (interrupted) {
                    return 0;
                }
                byte[] pcm = TtsPcm16AudioConverter.fromMonoFloat(samples);
                if (pcm.length > 0) {
                    sink.accept(pcm);
                }
                return 1;
            });
            if (interrupted) {
                env.info("Sherpa ONNX TTS synthesis interrupted: " + request.text());
            } else {
                env.info("Sherpa ONNX TTS synthesis completed: " + request.text());
            }
        } catch (Throwable t) {
            TtsRuntimeFailurePolicy.rethrowFatal(t);
            env.error("Sherpa ONNX TTS synthesis failed: " + request.text(), t);
            throw new IllegalStateException("Sherpa ONNX TTS synthesis failed", t);
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
    }

    @Override
    public synchronized void shutdown() {
        interrupted = true;
        if (tts != null) {
            tts.release();
            tts = null;
        }
        initialized = false;
        sampleRate = 0;
    }

    private float resolveSpeed(TtsRequest request) {
        float speed = request.voiceProfile().speed() > 0.0f ? request.voiceProfile().speed() : defaultSpeed;
        return Math.max(0.1f, Math.min(5.0f, speed));
    }

    private int resolveSpeakerId(TtsRequest request) {
        return Math.max(0, request.voiceProfile().speakerId() >= 0 ? request.voiceProfile().speakerId() : defaultSpeakerId);
    }
}
