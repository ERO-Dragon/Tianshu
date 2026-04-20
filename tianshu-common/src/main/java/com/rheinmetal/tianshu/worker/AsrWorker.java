package com.rheinmetal.tianshu.worker;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.AsrEngine;
import com.rheinmetal.tianshu.event.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class AsrWorker implements Runnable {
    private static final byte[] POISON_PILL = new byte[0];

    private final IAudioBridge audioManager;
    private final TianshuCoreManager coreManager;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final BlockingQueue<byte[]> audioQueue;
    private final BlockingQueue<TianshuEvent> asrQueue;
    private boolean running = true;
    private final AtomicInteger turnId = new AtomicInteger(0);
    private boolean isStreaming = false;

    public AsrWorker(IAudioBridge audioManager, TianshuCoreManager coreManager, IGameEnvironment env, ITianshuConfig config) {
        this.audioManager = audioManager;
        this.coreManager = coreManager;
        this.env = env;
        this.config = config;
        this.audioQueue = new LinkedBlockingQueue<>();
        this.asrQueue = coreManager.getEventBus().getAsrQueue();
    }

    private AsrEngine getAsrEngine() {
        return coreManager.getAsrEngine();
    }

    @Override
    public void run() {
        env.info("ASR Worker 启动，进入待机状态");

        try {
            while (running) {
                TianshuEvent event = asrQueue.take();

                if (event instanceof InterruptEvent) {
                    handleInterruptEvent();
                } else if (event instanceof StartListeningEvent) {
                    handleStartListening();
                } else if (event instanceof StopListeningEvent) {
                    handleStopListening();
                } else if (event instanceof StartStreamRecordingEvent) {
                    handleStartStreamRecording();
                } else if (event instanceof StopStreamRecordingEvent) {
                    handleStopStreamRecording();
                } else if (event instanceof ForceAsrFlushEvent) {
                    handleForceFlush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env.info("ASR Worker 被中断");
        } catch (Exception e) {
            env.error("ASR Worker 发生错误", e);
        } finally {
            cleanup();
            env.info("ASR Worker 停止");
        }
    }

    private void handleStartListening() {
        if (!coreManager.isEngineReady()) {
            env.warn("引擎未就绪，跳过录音");
            return;
        }
        isStreaming = false;
        audioManager.stopStreamRecording();
        env.info("ASR Worker 开始PTT录音");
        audioManager.startRecording();
    }

    private void handleStopListening() {
        if (!coreManager.isEngineReady()) {
            env.warn("引擎未就绪，跳过录音停止");
            env.displayMessageToPlayer("\u00a7e[\u5929\u6781] \u00a7f\u5929\u6781\u6b63\u5728\u82cf\u9192\uff0c\u8bf7\u7a0d\u5019...");
            return;
        }
        env.info("ASR Worker 停止PTT录音");
        byte[] audioData = audioManager.stopRecording();
        if (audioData != null && audioData.length > 0) {
            String result = getAsrEngine().recognizeComplete(audioData);
            if (!result.isEmpty()) {
                int currentTurnId = turnId.incrementAndGet();
                coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(result, currentTurnId));
                env.info("ASR 识别完成，turnId: " + currentTurnId);
            }
        }
    }

    private void handleForceFlush() {
        if (!coreManager.isEngineReady()) {
            env.displayMessageToPlayer("\u00a7e[\u5929\u6781] \u00a7f\u5929\u6781\u6b63\u5728\u82cf\u9192\uff0c\u8bf7\u7a0d\u5019...");
            return;
        }
        env.info("ASR Worker 收到强制截断指令");
        audioQueue.clear();
        String result = getAsrEngine().forceFlush();
        if (isMeaningfulText(result)) {
            int currentTurnId = turnId.incrementAndGet();
            coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(result, currentTurnId));
            env.info("ASR 强制截断识别完成，turnId: " + currentTurnId);
        }
    }

    private void handleStartStreamRecording() {
        if (!coreManager.isEngineReady()) {
            env.warn("引擎未就绪，跳过流式录音启动");
            env.displayMessageToPlayer("\u00a7e[\u5929\u6781] \u00a7f\u5929\u6781\u6b63\u5728\u82cf\u9192\uff0c\u8bf7\u7a0d\u5019...");
            return;
        }
        if (isStreaming) {
            return;
        }

        env.info("ASR Worker 开始流式录音");
        isStreaming = true;

        getAsrEngine().createStream();

        audioManager.startStreamRecording(chunk -> {
            try {
                audioQueue.put(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread audioProcessor = new Thread(() -> {
            try {
                while (isStreaming) {
                    byte[] chunk = audioQueue.take();
                    if (chunk == POISON_PILL) break;
                    String text = getAsrEngine().feedAudio(chunk);
                    if (!text.isEmpty()) {
                        coreManager.getEventBus().publishEvent(new AsrPartialTextEvent(text));
                    }
                    if (getAsrEngine().isEndpoint()) {
                        if (isMeaningfulText(text)) {
                            if (isWakeWordMode()) {
                                String wakeWord = config.getWakeWord();
                                if (text.contains(wakeWord)) {
                                    int currentTurnId = turnId.incrementAndGet();
                                    int index = text.indexOf(wakeWord) + wakeWord.length();
                                    String realCommand = text.substring(index).trim();
                                    if (isMeaningfulText(realCommand)) {
                                        coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(realCommand, currentTurnId));
                                    }
                                } else {
                                    env.info("ASR 断句完成，未命中唤醒词: " + wakeWord);
                                }
                            } else {
                                int currentTurnId = turnId.incrementAndGet();
                                coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(text, currentTurnId));
                                env.info("ASR 断句完成，turnId: " + currentTurnId);
                            }
                        }
                        getAsrEngine().reset();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ASR-Audio-Processor");
        audioProcessor.setDaemon(true);
        audioProcessor.start();
    }

    private void handleStopStreamRecording() {
        env.info("ASR Worker 收到停止流式指令，强制清理底层");
        isStreaming = false;
        audioQueue.clear();
        try { audioQueue.put(POISON_PILL); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (coreManager.isEngineReady()) {
            getAsrEngine().reset();
        }
        audioManager.stopStreamRecording();
    }

    private boolean isWakeWordMode() {
        return config.getTriggerMode() == TriggerMode.WAKE_WORD;
    }

    private boolean isMeaningfulText(String text) {
        if (text == null || text.isEmpty()) return false;
        String cleanText = text.replaceAll("[\\p{P}\\s\\p{C}]", "");
        return cleanText.length() >= 2;
    }

    private void handleInterruptEvent() {
        env.info("ASR Worker 收到打断事件");
        turnId.incrementAndGet();
        audioQueue.clear();
        if (isStreaming && coreManager.isEngineReady()) {
            getAsrEngine().reset();
        }
    }

    private void cleanup() {
        if (isStreaming) {
            handleStopStreamRecording();
        }
        audioManager.stopRecording();
        audioQueue.clear();
    }

    public void stop() {
        running = false;
        isStreaming = false;
        audioQueue.clear();
        try {
            asrQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
