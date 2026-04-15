package com.rheinmetal.tianshu.core.workers;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.core.TianshuEventBus;
import com.rheinmetal.tianshu.core.engine.TtsEngine;
import com.rheinmetal.tianshu.core.events.InterruptEvent;
import com.rheinmetal.tianshu.core.events.LlmChunkEvent;
import com.rheinmetal.tianshu.core.events.LlmEndEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class TtsWorker implements Runnable {
    private final AudioManager audioManager;
    private final TtsEngine ttsEngine;
    private final TianshuEventBus eventBus;
    private final BlockingQueue<com.rheinmetal.tianshu.core.events.TianshuEvent> ttsQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);
    private final StringBuilder textBuffer = new StringBuilder();

    public TtsWorker(AudioManager audioManager) {
        this.audioManager = audioManager;
        this.ttsEngine = new TtsEngine("http://127.0.0.1:8080");
        this.eventBus = TianshuEventBus.getInstance();
        this.ttsQueue = eventBus.getTtsQueue();
    }

    @Override
    public void run() {
        Tianshu.LOGGER.info("TTS Worker 启动");

        try {
            while (running) {
                // 阻塞监听队列
                com.rheinmetal.tianshu.core.events.TianshuEvent event = ttsQueue.take();

                if (event instanceof InterruptEvent) {
                    // 处理打断事件
                    handleInterruptEvent();
                    continue;
                }

                if (event instanceof LlmChunkEvent llmEvent) {
                    // 处理LLM文本块事件
                    int turnId = llmEvent.getTurnId();
                    // 检查turnId是否过期
                    if (turnId < currentTurnId.get()) {
                        Tianshu.LOGGER.info("TTS Worker 丢弃过期事件，turnId: {}", turnId);
                        continue;
                    }

                    // 更新当前turnId
                    currentTurnId.set(turnId);

                    // 缓存文本块
                    textBuffer.append(llmEvent.getText());

                    // 检查是否达到短句边界
                    if (isSentenceBoundary(textBuffer.toString())) {
                        // 合成并播放音频
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId);
                    }
                } else if (event instanceof LlmEndEvent llmEvent) {
                    // 处理LLM结束事件
                    int turnId = llmEvent.getTurnId();
                    // 检查turnId是否过期
                    if (turnId < currentTurnId.get()) {
                        Tianshu.LOGGER.info("TTS Worker 丢弃过期事件，turnId: {}", turnId);
                        continue;
                    }

                    // 处理剩余文本
                    if (textBuffer.length() > 0) {
                        String text = textBuffer.toString();
                        textBuffer.setLength(0);
                        synthesizeAndPlay(text, turnId);
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
            Tianshu.LOGGER.info("TTS Worker 停止");
        }
    }

    // 合成并播放音频
    private void synthesizeAndPlay(String text, int turnId) {
        try {
            ttsEngine.synthesizeSpeech(text, audio -> {
                // 检查turnId是否过期
                if (turnId < currentTurnId.get()) {
                    Tianshu.LOGGER.info("TTS Worker 丢弃过期音频，turnId: {}", turnId);
                    return;
                }
                // 播放音频
                audioManager.playAudio(audio);
            });
        } catch (Exception e) {
            Tianshu.LOGGER.error("TTS合成失败", e);
        }
    }

    // 检查是否达到短句边界
    private boolean isSentenceBoundary(String text) {
        return text.endsWith("。") || text.endsWith(".") || 
               text.endsWith("！") || text.endsWith("!") || 
               text.endsWith("？") || text.endsWith("?") || 
               text.endsWith(",") || text.endsWith(",");
    }

    // 停止Worker
    public void stop() {
        running = false;
        // 清理队列，让take()方法返回
        ttsQueue.clear();
        // 添加一个事件到队列，触发take()方法返回
        try {
            ttsQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关闭 TtsEngine
        if (ttsEngine != null) {
            ttsEngine.shutdown();
        }
    }

    // 处理打断事件
    private void handleInterruptEvent() {
        Tianshu.LOGGER.info("TTS Worker 收到打断事件，清空待合成文本");
        currentTurnId.incrementAndGet();
        textBuffer.setLength(0);
    }
}
