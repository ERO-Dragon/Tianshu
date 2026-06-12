package com.rheinmetal.tianshu.function.tts.synthesis;

import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.model.ModelSettings;

import java.util.Map;

public final class SherpaOnnxTtsBackend implements TtsBackend {
    private final IGameEnvironment env;
    private final SherpaOnnxTtsConfigFactory configFactory;
    private OfflineTts tts;
    private volatile boolean initialized;
    private volatile boolean interrupted;
    private volatile boolean zipVoice;
    private int sampleRate;
    private float defaultSpeed = 1.0f;
    private int defaultSpeakerId;

    public SherpaOnnxTtsBackend(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.configFactory = new SherpaOnnxTtsConfigFactory(env, config);
    }

    @Override
    public synchronized boolean initialize(TtsResolvedModel model) {
        shutdown();
        try {
            SherpaOnnxTtsConfigFactory.ResolvedConfig resolvedConfig = configFactory.build(model).orElse(null);
            if (resolvedConfig == null) {
                return false;
            }
            tts = new OfflineTts(resolvedConfig.config());
            sampleRate = tts.getSampleRate();
            zipVoice = resolvedConfig.zipVoice();
            ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(model.modelDir());
            defaultSpeed = (float) settings.speed;
            defaultSpeakerId = settings.speakerId;
            initialized = true;
            env.info("Sherpa ONNX TTS backend initialized, sampleRate=" + sampleRate + "Hz, speakers=" + tts.getNumSpeakers());
            return true;
        } catch (Throwable t) {
            env.error("Sherpa ONNX TTS backend initialization failed", t);
            shutdown();
            return false;
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
        if (zipVoice && request.voiceProfile().referenceAudio().length > 0) {
            synthesizeZipVoice(current, request, sink, speed, speakerId);
            return;
        }
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
        zipVoice = false;
        sampleRate = 0;
    }

    private float resolveSpeed(TtsRequest request) {
        float speed = request.voiceProfile().speed() > 0.0f ? request.voiceProfile().speed() : defaultSpeed;
        return Math.max(0.1f, Math.min(5.0f, speed));
    }

    private int resolveSpeakerId(TtsRequest request) {
        return Math.max(0, request.voiceProfile().speakerId() >= 0 ? request.voiceProfile().speakerId() : defaultSpeakerId);
    }

    private void synthesizeZipVoice(OfflineTts current, TtsRequest request, TtsAudioSink sink, float speed, int speakerId) {
        env.info("ZipVoice synthesis started: " + request.text() + " (voice=" + request.voiceProfile().voiceId() + ")");
        try {
            GenerationConfig config = new GenerationConfig();
            config.setSpeed(speed);
            config.setSid(speakerId);
            config.setReferenceAudio(request.voiceProfile().referenceAudio());
            config.setReferenceSampleRate(request.voiceProfile().referenceSampleRate());
            config.setReferenceText(request.voiceProfile().referenceText());
            config.setExtra(Map.of());
            current.generateWithConfigAndCallback(request.text(), config, samples -> {
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
                env.info("ZipVoice synthesis interrupted: " + request.text());
            } else {
                env.info("ZipVoice synthesis completed: " + request.text());
            }
        } catch (Throwable t) {
            env.error("ZipVoice synthesis failed: " + request.text(), t);
            throw new IllegalStateException("ZipVoice synthesis failed", t);
        }
    }
}
