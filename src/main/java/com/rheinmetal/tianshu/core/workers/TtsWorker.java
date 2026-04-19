package com.rheinmetal.tianshu.core.workers;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.core.TianshuEventBus;
import com.rheinmetal.tianshu.core.engine.TtsEngine;
import com.rheinmetal.tianshu.core.events.InterruptEvent;
import com.rheinmetal.tianshu.core.events.LlmChunkEvent;
import com.rheinmetal.tianshu.core.events.LlmEndEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class TtsWorker implements Runnable {
    private static final int MAX_BUFFER_LENGTH = 200;

    private final AudioManager audioManager;
    private final TtsEngine ttsEngine;
    private final TianshuEventBus eventBus;
    private final BlockingQueue<com.rheinmetal.tianshu.core.events.TianshuEvent> ttsQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean synthesizing = false;

    public TtsWorker(AudioManager audioManager) {
        this.audioManager = audioManager;
        this.ttsEngine = new TtsEngine();
        this.eventBus = TianshuEventBus.getInstance();
        this.ttsQueue = eventBus.getTtsQueue();
    }

    private void ensureEngineInitialized() {
        if (!ttsEngine.isInitialized()) {
            String modelDir = Config.getTtsModelPath().toString();
            Tianshu.LOGGER.info("TTS Worker 首次触发，初始化引擎，模型目录: {}", modelDir);
            ttsEngine.initialize(modelDir);
            if (ttsEngine.isInitialized()) {
                Tianshu.LOGGER.info("TTS 引擎初始化成功，采样率: {}Hz", ttsEngine.getSampleRate());
            } else {
                Tianshu.LOGGER.error("TTS 引擎初始化失败");
            }
        }
    }

    @Override
    public void run() {
        Tianshu.LOGGER.info("TTS Worker 启动");

        try {
            while (running) {
                com.rheinmetal.tianshu.core.events.TianshuEvent event = ttsQueue.take();

                if (event instanceof InterruptEvent) {
                    handleInterruptEvent();
                    continue;
                }

                if (event instanceof LlmChunkEvent llmEvent) {
                    int turnId = llmEvent.getTurnId();
                    if (turnId < currentTurnId.get()) {
                        Tianshu.LOGGER.info("TTS Worker 丢弃过期事件，turnId: {}", turnId);
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
                        Tianshu.LOGGER.info("TTS Worker 丢弃过期结束事件，turnId: {}", turnId);
                        continue;
                    }

                    if (textBuffer.length() > 0) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId);
                    }

                    if (synthesizing) {
                        audioManager.stopTtsPlayback(); // 内部的 drain() 会自动等播完
                        synthesizing = false;
                        Tianshu.LOGGER.info("TTS 流式播放通道已播完并关闭");
                    }

                    Tianshu.LOGGER.info("TTS Worker 处理完成，turnId: {}", turnId);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Tianshu.LOGGER.info("TTS Worker 被中断");
        } catch (Exception e) {
            Tianshu.LOGGER.error("TTS Worker 发生错误", e);
        } finally {
            audioManager.stopTtsPlayback();
            Tianshu.LOGGER.info("TTS Worker 停止");
        }
    }

    private void synthesizeAndPlay(String text, int turnId) {
        ensureEngineInitialized();
        if (!ttsEngine.isInitialized()) {
            Tianshu.LOGGER.warn("TTS 引擎未就绪，跳过合成: {}", text);
            return;
        }

        if (!synthesizing) {
            audioManager.startTtsPlayback(ttsEngine.getSampleRate());
            synthesizing = true;
        }

        try {
            ttsEngine.synthesizeSpeech(text, audio -> {
                if (turnId < currentTurnId.get()) {
                    Tianshu.LOGGER.debug("TTS Worker 丢弃过期音频块，turnId: {}", turnId);
                    return;
                }
                audioManager.feedTtsAudio(audio);
            });
        } catch (Exception e) {
            Tianshu.LOGGER.error("TTS合成失败: {}", text, e);
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
        Tianshu.LOGGER.info("TTS Worker 收到打断事件，清空待合成文本");
        currentTurnId.incrementAndGet();
        textBuffer.setLength(0);
        audioManager.stopTtsPlayback();
        synthesizing = false;
    }
}
