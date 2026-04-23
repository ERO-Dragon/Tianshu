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
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong streamingSessionId = new AtomicLong(0L);
    private volatile boolean isStreaming = false;
    private volatile Thread audioProcessorThread;
    private volatile AsrEngine.StreamingSession streamingEngineSession;

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

                if (event instanceof InterruptEvent interruptEvent) {
                    handleInterruptEvent(interruptEvent);
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
        if (!coreManager.canAcceptVoiceInput()) {
            env.warn("ASR 未就绪，跳过录音");
            return;
        }
        isStreaming = false;
        audioManager.stopStreamRecording();
        releaseStreamingEngineSession();
        long sessionId = coreManager.interruptOngoingProcessing();
        env.info("ASR Worker 开始PTT录音，sessionId=" + sessionId);
        audioManager.startRecording();
    }

    private void handleStopListening() {
        if (!coreManager.isAsrReady()) {
            env.warn("ASR 未就绪，跳过录音停止");
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§e[天极] §f天极正在苏醒，请稍候..."));
            return;
        }
        env.info("ASR Worker 停止PTT录音");
        byte[] audioData = audioManager.stopRecording();
        if (audioData == null || audioData.length == 0) {
            env.warn("PTT 录音数据为空，跳过识别");
            return;
        }

        env.info("ASR Worker 开始完整识别，音频长度=" + audioData.length + " bytes");
        try {
            String result = getAsrEngine().recognizeComplete(audioData);
            env.info("ASR Worker 完整识别返回，文本长度=" + (result != null ? result.length() : -1));
            if (result != null && !result.isEmpty()) {
                long sessionId = coreManager.interruptOngoingProcessing();
                int currentTurnId = turnId.incrementAndGet();
                coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(result, currentTurnId, sessionId));
                env.info("ASR 识别完成，turnId: " + currentTurnId + ", sessionId=" + sessionId);
            } else {
                env.info("ASR 完整识别结果为空");
            }
        } catch (Exception e) {
            env.error("ASR 完整识别失败", e);
        }
    }

    private void handleForceFlush() {
        if (!coreManager.isAsrReady()) {
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§e[天极] §f天极正在苏醒，请稍候..."));
            return;
        }
        env.info("ASR Worker 收到强制截断指令");
        audioQueue.clear();
        String result = getAsrEngine().forceFlush(streamingEngineSession);
        if (isMeaningfulText(result)) {
            long sessionId = coreManager.interruptOngoingProcessing();
            int currentTurnId = turnId.incrementAndGet();
            coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(result, currentTurnId, sessionId));
            env.info("ASR 强制截断识别完成，turnId: " + currentTurnId + ", sessionId=" + sessionId);
        }
    }

    private void handleStartStreamRecording() {
        if (!coreManager.isAsrReady()) {
            env.warn("ASR 未就绪，跳过流式录音启动");
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§e[天极] §f天极正在苏醒，请稍候..."));
            return;
        }
        if (isStreaming) {
            return;
        }

        env.info("ASR Worker 开始流式录音");
        isStreaming = true;
        long sessionId = coreManager.getEventBus().getActiveSessionId();
        streamingSessionId.set(sessionId);
        streamingEngineSession = getAsrEngine().createStreamingSession();
        if (streamingEngineSession == null) {
            isStreaming = false;
            return;
        }

        audioManager.startStreamRecording(chunk -> {
            try {
                if (isStreaming && streamingSessionId.get() == sessionId) {
                    audioQueue.put(chunk);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        audioProcessorThread = new Thread(() -> processStreamingAudio(sessionId), "ASR-Audio-Processor");
        audioProcessorThread.setDaemon(true);
        audioProcessorThread.start();
    }

    private void processStreamingAudio(long sessionId) {
        AsrEngine.StreamingSession session = streamingEngineSession;
        try {
            while (isStreaming && streamingSessionId.get() == sessionId && session != null) {
                byte[] chunk = audioQueue.take();
                if (chunk == POISON_PILL) break;
                String text = getAsrEngine().feedAudio(session, chunk);
                if (!text.isEmpty()) {
                    coreManager.getEventBus().publishEvent(new AsrPartialTextEvent(text));
                }
                if (getAsrEngine().isEndpoint(session)) {
                    if (isMeaningfulText(text)) {
                        if (isWakeWordMode()) {
                            String wakeWord = config.getWakeWord();
                            if (text.contains(wakeWord)) {
                                long newSessionId = coreManager.interruptOngoingProcessing();
                                int currentTurnId = turnId.incrementAndGet();
                                int index = text.indexOf(wakeWord) + wakeWord.length();
                                String realCommand = text.substring(index).trim();
                                if (isMeaningfulText(realCommand)) {
                                    coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(realCommand, currentTurnId, newSessionId));
                                }
                            } else {
                                env.info("ASR 断句完成，未命中唤醒词: " + wakeWord);
                            }
                        } else {
                            long newSessionId = coreManager.interruptOngoingProcessing();
                            int currentTurnId = turnId.incrementAndGet();
                            coreManager.getEventBus().publishEvent(new AsrFinalTextEvent(text, currentTurnId, newSessionId));
                            env.info("ASR 断句完成，turnId: " + currentTurnId + ", sessionId=" + newSessionId);
                        }
                    }
                    getAsrEngine().reset(session);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleStopStreamRecording() {
        env.info("ASR Worker 收到停止流式指令，强制清理底层");
        isStreaming = false;
        audioQueue.clear();
        try {
            audioQueue.put(POISON_PILL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (coreManager.isAsrReady()) {
            getAsrEngine().reset(streamingEngineSession);
        }
        audioManager.stopStreamRecording();
        Thread thread = audioProcessorThread;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        audioProcessorThread = null;
        releaseStreamingEngineSession();
    }

    private void releaseStreamingEngineSession() {
        AsrEngine.StreamingSession session = streamingEngineSession;
        streamingEngineSession = null;
        if (session != null && coreManager.isAsrReady()) {
            getAsrEngine().releaseStreamingSession(session);
        }
    }

    private boolean isWakeWordMode() {
        return config.getTriggerMode() == TriggerMode.WAKE_WORD;
    }

    private boolean isMeaningfulText(String text) {
        if (text == null || text.isEmpty()) return false;
        String cleanText = text.replaceAll("[\\p{P}\\s\\p{C}]", "");
        return cleanText.length() >= 1;
    }

    private void handleInterruptEvent(InterruptEvent interruptEvent) {
        env.info("ASR Worker 收到打断事件，sessionId=" + interruptEvent.getSessionId());
        audioQueue.clear();
        streamingSessionId.set(interruptEvent.getSessionId());
        if (isStreaming && coreManager.isAsrReady()) {
            getAsrEngine().reset(streamingEngineSession);
        }
    }

    private void cleanup() {
        if (isStreaming) {
            handleStopStreamRecording();
        }
        audioManager.stopRecording();
        audioQueue.clear();
        releaseStreamingEngineSession();
    }

    public void stop() {
        running = false;
        isStreaming = false;
        audioQueue.clear();
        try {
            asrQueue.put(new InterruptEvent(streamingSessionId.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isStreaming() {
        return isStreaming;
    }
}
