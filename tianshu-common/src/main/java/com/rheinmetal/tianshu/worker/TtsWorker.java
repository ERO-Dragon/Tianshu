package com.rheinmetal.tianshu.worker;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.TtsEngine;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
            String modelDir = config.getTtsModelPath().toString();
            env.info("TTS Worker 首次触发，初始化引擎，模型目录: " + modelDir);

            TtsModelInfo info = coreManager.resolveCurrentTtsModelInfo();
            if (info != null) {
                String vocoderPath = coreManager.resolveVocoderPath(config.getTtsModelPath());
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

                if (event instanceof InterruptEvent) {
                    handleInterruptEvent();
                    continue;
                }

                if (event instanceof LlmChunkEvent llmEvent) {
                    int turnId = llmEvent.getTurnId();
                    if (turnId < currentTurnId.get()) {
                        env.info("TTS Worker 丢弃过期事件，turnId: " + turnId);
                        continue;
                    }

                    currentTurnId.set(turnId);
                    textBuffer.append(llmEvent.getText());

                    if (isSentenceBoundary(textBuffer.toString()) || textBuffer.length() > MAX_BUFFER_LENGTH) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId);
                    }
                } else if (event instanceof LlmEndEvent llmEvent) {
                    int turnId = llmEvent.getTurnId();
                    if (turnId < currentTurnId.get()) {
                        env.info("TTS Worker 丢弃过期结束事件，turnId: " + turnId);
                        continue;
                    }

                    if (textBuffer.length() > 0) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId);
                    }

                    if (synthesizing) {
                        audioManager.stopTtsPlayback();
                        synthesizing = false;
                        env.info("TTS 流式播放通道已播完并关闭");
                    }

                    env.info("TTS Worker 处理完成，turnId: " + turnId);
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

    private void synthesizeAndPlay(String text, int turnId) {
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
                if (turnId < currentTurnId.get()) {
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
               text.endsWith("；") || text.endsWith(";") ||
               text.endsWith(",") || text.endsWith(",");
    }

    public void stop() {
        running = false;
        ttsQueue.clear();
        try {
            ttsQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        audioManager.stopTtsPlayback();
        if (ttsEngine != null) {
            ttsEngine.shutdown();
        }
    }

    private void handleInterruptEvent() {
        env.info("TTS Worker 收到打断事件，清空待合成文本");
        currentTurnId.incrementAndGet();
        textBuffer.setLength(0);
        audioManager.stopTtsPlayback();
        synthesizing = false;
    }

    public boolean isEngineInitialized() {
        return ttsEngine != null && ttsEngine.isInitialized();
    }
}
