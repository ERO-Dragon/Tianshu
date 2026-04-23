package com.rheinmetal.tianshu.worker;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.TtsEngine;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TtsWorker implements Runnable {
    private static final int MAX_BUFFER_LENGTH = 200;

    private final IAudioBridge audioManager;
    private final TianshuCoreManager coreManager;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final TtsEngine ttsEngine;
    private final BlockingQueue<TianshuEvent> ttsQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);
    private final AtomicLong currentSessionId = new AtomicLong(0L);
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean synthesizing = false;

    public TtsWorker(IAudioBridge audioManager, TianshuCoreManager coreManager, IGameEnvironment env, ITianshuConfig config) {
        this.audioManager = audioManager;
        this.coreManager = coreManager;
        this.env = env;
        this.config = config;
        this.ttsEngine = new TtsEngine(env, config);
        this.ttsQueue = coreManager.getEventBus().getTtsQueue();
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

    @Override
    public void run() {
        env.info("TTS Worker 启动");

        try {
            while (running) {
                TianshuEvent event = ttsQueue.take();

                if (event instanceof InterruptEvent interruptEvent) {
                    handleInterruptEvent(interruptEvent);
                    continue;
                }

                if (event instanceof LlmChunkEvent llmEvent) {
                    int turnId = llmEvent.getTurnId();
                    long sessionId = llmEvent.getSessionId();
                    if (!isCurrent(turnId, sessionId)) {
                        env.info("TTS Worker 丢弃过期事件，turnId: " + turnId + ", sessionId=" + sessionId);
                        continue;
                    }

                    currentTurnId.set(turnId);
                    currentSessionId.set(sessionId);
                    textBuffer.append(llmEvent.getText());

                    if (isSentenceBoundary(textBuffer.toString()) || textBuffer.length() > MAX_BUFFER_LENGTH) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId, sessionId);
                    }
                } else if (event instanceof LlmEndEvent llmEvent) {
                    int turnId = llmEvent.getTurnId();
                    long sessionId = llmEvent.getSessionId();
                    if (!isCurrent(turnId, sessionId)) {
                        env.info("TTS Worker 丢弃过期结束事件，turnId: " + turnId + ", sessionId=" + sessionId);
                        continue;
                    }

                    if (textBuffer.length() > 0) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId, sessionId);
                    }

                    if (synthesizing) {
                        audioManager.stopTtsPlayback();
                        synthesizing = false;
                        env.info("TTS 流式播放通道已播完并关闭");
                    }

                    env.info("TTS Worker 处理完成，turnId: " + turnId + ", sessionId=" + sessionId + ", cancelled=" + llmEvent.isCancelled() + ", error=" + llmEvent.getErrorMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env.info("TTS Worker 被中断");
        } catch (Exception e) {
            env.error("TTS Worker 发生错误", e);
        } finally {
            audioManager.stopTtsPlayback();
            env.info("TTS Worker 停止");
        }
    }

    private boolean isCurrent(int turnId, long sessionId) {
        return turnId >= currentTurnId.get() && sessionId == currentSessionId.get() && coreManager.getEventBus().isCurrentSession(sessionId);
    }

    private Path resolveActiveModelPath() {
        Path modelPath = coreManager.resolveCurrentTtsModelDir();
        return modelPath != null ? modelPath : config.getTtsModelPath();
    }

    private TtsModelInfo resolveCurrentTtsModelInfo(Path modelPath) {
        if (modelPath == null || modelPath.getFileName() == null) {
            return null;
        }
        String dirName = modelPath.getFileName().toString();
        for (TtsModelInfo info : coreManager.getModelManager().loadTtsModelCatalog()) {
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

    private String cleanForTts(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";
        String cleaned = rawText.replace('\r', ' ').replace('\n', ' ');
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
        ttsQueue.clear();
        try {
            ttsQueue.put(new InterruptEvent(currentSessionId.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private void handleInterruptEvent(InterruptEvent interruptEvent) {
        long sessionId = interruptEvent.getSessionId();
        currentSessionId.set(sessionId);
        textBuffer.setLength(0);
        interruptSynthesis();
        env.info("TTS Worker 收到中断事件，sessionId=" + sessionId);
    }
}
