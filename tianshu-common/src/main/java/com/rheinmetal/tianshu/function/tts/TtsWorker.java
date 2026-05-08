package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.event.TtsPlaybackEndEvent;
import com.rheinmetal.tianshu.function.tts.engine.TtsEngine;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class TtsWorker {
    private static final int MAX_BUFFER_LENGTH = 200;

    private final IAudioBridge audioManager;
    private final TianshuEventBus eventBus;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final TtsModelService modelService;
    private final Supplier<ModelManager> modelManagerSupplier;
    private final TtsEngine ttsEngine;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);
    private final AtomicLong currentSessionId = new AtomicLong(0L);
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean synthesizing = false;

    public TtsWorker(IAudioBridge audioManager, TianshuEventBus eventBus, IGameEnvironment env, ITianshuConfig config, TtsModelService modelService, Supplier<ModelManager> modelManagerSupplier) {
        this.audioManager = audioManager;
        this.eventBus = eventBus;
        this.env = env;
        this.config = config;
        this.modelService = modelService;
        this.modelManagerSupplier = modelManagerSupplier;
        this.ttsEngine = new TtsEngine(env, config);
    }

    public void initEngine() {
        if (ttsEngine.isInitialized()) return;
        ensureEngineInitialized();
    }

    private void ensureEngineInitialized() {
        if (!ttsEngine.isInitialized()) {
            Path modelPath = resolveActiveModelPath();
            String modelDir = modelPath.toString();
            env.info("TTS Worker 首次触发，初始化引擎，模型目录: " + modelDir);

            TtsModelInfo info = resolveCurrentTtsModelInfo(modelPath);
            if (info != null) {
                String vocoderPath = resolveVocoderPath(modelPath);
                if (info.needVocoder && vocoderPath != null) {
                    ttsEngine.initialize(modelDir, info, vocoderPath);
                } else {
                    ttsEngine.initialize(modelDir, info);
                }
            } else {
                ttsEngine.initialize(modelDir);
            }

            if (ttsEngine.isInitialized()) {
                env.info("TTS 引擎初始化成功，采样率: " + ttsEngine.getSampleRate() + "Hz");
            } else {
                env.error("TTS 引擎初始化失败", null);
            }
        }
    }

    private boolean isCurrent(int turnId, long sessionId) {
        if (turnId < 0) {
            return eventBus.isCurrentSession(sessionId);
        }
        return turnId >= currentTurnId.get() && sessionId == currentSessionId.get() && eventBus.isCurrentSession(sessionId);
    }

    public void handleProtocolChunk(String text) {
        if (!running || text == null || text.isBlank()) {
            return;
        }
        long sessionId = eventBus.getActiveSessionId();
        currentSessionId.set(sessionId);
        textBuffer.append(text);
        if (isSentenceBoundary(textBuffer.toString()) || textBuffer.length() > MAX_BUFFER_LENGTH) {
            String bufferedText = textBuffer.toString();
            textBuffer.setLength(0);
            synthesizeAndPlay(bufferedText, -1, sessionId);
        }
    }

    public void finishProtocolPlayback() {
        if (!running) {
            return;
        }
        long sessionId = eventBus.getActiveSessionId();
        if (textBuffer.length() > 0) {
            String bufferedText = textBuffer.toString();
            textBuffer.setLength(0);
            synthesizeAndPlay(bufferedText, -1, sessionId);
        }
        if (synthesizing) {
            audioManager.setOnPlaybackFinished(() -> eventBus.publishEvent(new TtsPlaybackEndEvent("llm", sessionId)));
            audioManager.finishTtsPlayback();
            synthesizing = false;
        }
    }

    public void speakProtocolText(String text, boolean interruptCurrent) {
        if (interruptCurrent) {
            interruptSynthesis();
            currentSessionId.set(eventBus.getActiveSessionId());
        }
        handleProtocolChunk(text);
        finishProtocolPlayback();
    }

    public ExecutionLane currentSynthesisLane() {
        TtsModelInfo info = resolveCurrentTtsModelInfo(resolveActiveModelPath());
        if (info != null && "moss".equals(info.getEngineType())) {
            return ExecutionLane.TTS_AUTOREGRESSIVE;
        }
        if (ttsEngine.isMossEngine()) {
            return ExecutionLane.TTS_AUTOREGRESSIVE;
        }
        return ExecutionLane.TTS_FAST;
    }

    private Path resolveActiveModelPath() {
        Path modelPath = modelService.resolveCurrentModelDir();
        return modelPath != null ? modelPath : config.getTtsModelPath();
    }

    private TtsModelInfo resolveCurrentTtsModelInfo(Path modelPath) {
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        for (TtsModelInfo info : modelManagerSupplier.get().loadTtsModelCatalog()) {
            if (info == null || info.name == null) {
                continue;
            }
            if (modelPath.endsWith(info.name) || info.name.equalsIgnoreCase(dirName)) {
                return info;
            }
            if ("zipvoice".equals(info.getEngineType()) && "ZipVoice".equalsIgnoreCase(dirName)) {
                return info;
            }
        }
        return null;
    }

    private String resolveVocoderPath(Path modelPath) {
        Path vocoderDir = modelPath.resolve("vocoders");
        if (!java.nio.file.Files.isDirectory(vocoderDir)) {
            return null;
        }
        try (var stream = java.nio.file.Files.list(vocoderDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".onnx"))
                    .map(p -> p.toAbsolutePath().toString())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            env.error("查找 vocoder 失败", e);
            return null;
        }
    }

    private void applyCurrentTtsSettings(Path modelPath, TtsModelInfo info) {
        if (modelPath == null) {
            ttsEngine.setVoiceSamplePath(null);
            return;
        }
        ModelSettings.TtsSettings settings = ModelSettings.loadTtsSettings(modelPath);
        ttsEngine.setSpeed((float) settings.speed);
        ttsEngine.setSpeakerId(settings.speakerId);
        ttsEngine.setVoiceSamplePath(resolveSelectedVoiceSample(settings, info));
    }

    private Path resolveSelectedVoiceSample(ModelSettings.TtsSettings settings, TtsModelInfo info) {
        if (info == null || !info.supportsVoiceClone()) {
            return null;
        }
        String selectedVoiceSample = settings.selectedVoiceSample;
        if (selectedVoiceSample == null || selectedVoiceSample.isBlank()) {
            return null;
        }
        Path voicePath = config.getVoiceLibraryPath().resolve(selectedVoiceSample).normalize();
        if (!Files.isRegularFile(voicePath)) {
            env.warn("选择的参考音色不存在，使用默认音色: " + selectedVoiceSample);
            return null;
        }
        return voicePath;
    }

    private String cleanForTts(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";
        String cleaned = rawText.replaceAll("<(?:think|reasoning|reflection)[^>]*>[\\s\\S]*?</(?:think|reasoning|reflection)>", "");
        cleaned = cleaned.replaceAll("<(?:think|reasoning|reflection)[^>]*/>", "");
        cleaned = cleaned.replace('\r', ' ').replace('\n', ' ');
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private void synthesizeAndPlay(String rawText, int turnId, long sessionId) {
        ttsEngine.resetInterrupt();
        String text = cleanForTts(rawText);

        if (text.isEmpty()) {
            return;
        }

        if (!isCurrent(turnId, sessionId)) return;

        ensureEngineInitialized();
        if (!ttsEngine.isInitialized()) {
            env.warn("TTS 引擎未就绪，跳过合成: " + text);
            return;
        }
        Path modelPath = resolveActiveModelPath();
        TtsModelInfo info = resolveCurrentTtsModelInfo(modelPath);
        applyCurrentTtsSettings(modelPath, info);

        if (!synthesizing) {
            audioManager.startTtsPlayback(ttsEngine.getSampleRate());
            synthesizing = true;
        }

        try {
            ttsEngine.synthesizeSpeech(text, audio -> {
                if (!isCurrent(turnId, sessionId)) {
                    return;
                }
                audioManager.feedTtsAudio(audio);
            });
        } catch (Exception e) {
            env.error("TTS合成失败: " + text, e);
        }
    }

    private boolean isSentenceBoundary(String text) {
        return text.endsWith("。") || text.endsWith(".") ||
               text.endsWith("！") || text.endsWith("!") ||
               text.endsWith("？") || text.endsWith("?") ||
               text.endsWith("；") || text.endsWith(";");
    }

    public void stop() {
        running = false;
        interruptSynthesis();
        audioManager.stopTtsPlayback();
        ttsEngine.shutdown();
    }

    public void shutdownEngine() {
        ttsEngine.shutdown();
    }

    public void interruptSynthesis() {
        ttsEngine.interrupt();
        textBuffer.setLength(0);
        if (synthesizing) {
            audioManager.stopTtsPlayback();
            synthesizing = false;
        }
    }

    public boolean isEngineInitialized() {
        return ttsEngine.isInitialized();
    }

    public int getSampleRate() {
        return ttsEngine.getSampleRate();
    }

    public void synthesizeForPreview(String text, float speed, java.util.function.Consumer<byte[]> onAudio) {
        if (!ttsEngine.isInitialized()) return;
        float origSpeed = speed;
        ttsEngine.setSpeed(speed);
        try {
            ttsEngine.resetInterrupt();
            ttsEngine.synthesizeSpeech(text, onAudio);
        } finally {
            ttsEngine.setSpeed(origSpeed);
        }
    }
}
