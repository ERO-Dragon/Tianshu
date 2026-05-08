package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class AsrWorker implements Runnable {
    private static final byte[] POISON_PILL = new byte[0];

    private final IAudioBridge audioManager;
    private final TianshuEventBus eventBus;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final Supplier<AsrEngine> engineSupplier;
    private final BooleanSupplier asrReady;
    private final BooleanSupplier voiceInputAcceptance;
    private final LongSupplier interruptProcessing;
    private final BlockingQueue<byte[]> audioQueue;
    private final BlockingQueue<TianshuEvent> asrQueue;
    private boolean running = true;
    private final AtomicInteger turnId = new AtomicInteger(0);
    private final AtomicLong streamingSessionId = new AtomicLong(0L);
    private volatile boolean isStreaming = false;
    private volatile ProtocolTaskHandle audioProcessorTask;
    private volatile AsrEngine.StreamingSession streamingEngineSession;
    private final AsrProtocolAdapter protocolAdapter;

    public AsrWorker(IAudioBridge audioManager, TianshuEventBus eventBus, ProtocolRuntime protocolRuntime, IGameEnvironment env, ITianshuConfig config, Supplier<AsrEngine> engineSupplier, BooleanSupplier asrReady, BooleanSupplier voiceInputAcceptance, LongSupplier interruptProcessing) {
        this.audioManager = audioManager;
        this.eventBus = eventBus;
        this.env = env;
        this.config = config;
        this.engineSupplier = engineSupplier;
        this.asrReady = asrReady;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.interruptProcessing = interruptProcessing;
        this.audioQueue = new LinkedBlockingQueue<>();
        this.asrQueue = eventBus.getAsrQueue();
        this.protocolAdapter = new AsrProtocolAdapter(protocolRuntime);
    }

    private AsrEngine getAsrEngine() {
        return engineSupplier.get();
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
        if (!voiceInputAcceptance.getAsBoolean()) {
            env.warn("ASR 未就绪，跳过录音");
            return;
        }
        isStreaming = false;
        audioManager.stopStreamRecording();
        releaseStreamingEngineSession();
        long sessionId = interruptProcessing.getAsLong();
        env.info("ASR Worker 开始PTT录音，sessionId=" + sessionId);
        audioManager.startRecording();
    }

    private void handleStopListening() {
        if (!asrReady.getAsBoolean()) {
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
                long sessionId = interruptProcessing.getAsLong();
                int currentTurnId = turnId.incrementAndGet();
                publishFinalText(result, result, currentTurnId, sessionId, "push_to_talk", false);
                env.info("ASR 识别完成，turnId: " + currentTurnId + ", sessionId=" + sessionId);
            } else {
                env.info("ASR 完整识别结果为空");
            }
        } catch (Exception e) {
            env.error("ASR 完整识别失败", e);
        }
    }

    private void handleForceFlush() {
        if (!asrReady.getAsBoolean()) {
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§e[天极] §f天极正在苏醒，请稍候..."));
            return;
        }
        env.info("ASR Worker 收到强制截断指令");
        audioQueue.clear();
        String result = getAsrEngine().forceFlush(streamingEngineSession);
        if (isMeaningfulText(result)) {
            if (isWakeWordMode()) {
                String wakeWord = config.getWakeWord();
                if (result.contains(wakeWord)) {
                    long sessionId = interruptProcessing.getAsLong();
                    int currentTurnId = turnId.incrementAndGet();
                    int index = result.indexOf(wakeWord) + wakeWord.length();
                    String realCommand = result.substring(index).trim();
                    if (isMeaningfulText(realCommand)) {
                        publishFinalText(realCommand, result, currentTurnId, sessionId, "force_flush", true);
                        env.info("ASR 强制截断命中唤醒词，提取命令: " + realCommand + ", turnId: " + currentTurnId);
                    } else {
                        env.info("ASR 强制截断命中唤醒词，但唤醒词后无有效命令");
                    }
                } else {
                    env.info("ASR 强制截断未命中唤醒词: " + wakeWord);
                }
            } else {
                long sessionId = interruptProcessing.getAsLong();
                int currentTurnId = turnId.incrementAndGet();
                publishFinalText(result, result, currentTurnId, sessionId, "force_flush", false);
                env.info("ASR 强制截断识别完成，turnId: " + currentTurnId + ", sessionId=" + sessionId);
            }
        }
    }

    private void handleStartStreamRecording() {
        if (!asrReady.getAsBoolean()) {
            env.warn("ASR 未就绪，跳过流式录音启动");
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§e[天极] §f天极正在苏醒，请稍候..."));
            return;
        }
        if (isStreaming) {
            return;
        }

        env.info("ASR Worker 开始流式录音");
        isStreaming = true;
        long sessionId = eventBus.getActiveSessionId();
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

        audioProcessorTask = protocolAdapter.submitStreamProcessor(() -> processStreamingAudio(sessionId));
    }

    private void processStreamingAudio(long sessionId) {
        AsrEngine.StreamingSession session = streamingEngineSession;
        try {
            while (isStreaming && streamingSessionId.get() == sessionId && session != null) {
                byte[] chunk = audioQueue.take();
                if (chunk == POISON_PILL) break;
                String text = getAsrEngine().feedAudio(session, chunk);
                if (!text.isEmpty()) {
                    eventBus.publishEvent(new AsrPartialTextEvent(text));
                }
                if (getAsrEngine().isEndpoint(session)) {
                    if (isMeaningfulText(text)) {
                        if (isWakeWordMode()) {
                            String wakeWord = config.getWakeWord();
                            if (text.contains(wakeWord)) {
                                long newSessionId = interruptProcessing.getAsLong();
                                int currentTurnId = turnId.incrementAndGet();
                                int index = text.indexOf(wakeWord) + wakeWord.length();
                                String realCommand = text.substring(index).trim();
                                if (isMeaningfulText(realCommand)) {
                                    publishFinalText(realCommand, text, currentTurnId, newSessionId, "stream", true);
                                }
                            } else {
                                env.info("ASR 断句完成，未命中唤醒词: " + wakeWord);
                            }
                        } else {
                            long newSessionId = interruptProcessing.getAsLong();
                            int currentTurnId = turnId.incrementAndGet();
                            publishFinalText(text, text, currentTurnId, newSessionId, "stream", false);
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
        if (asrReady.getAsBoolean()) {
            getAsrEngine().reset(streamingEngineSession);
        }
        audioManager.stopStreamRecording();
        ProtocolTaskHandle task = audioProcessorTask;
        if (task != null && !task.isDone()) {
            task.cancel("ASR stream stopped");
        }
        audioProcessorTask = null;
        releaseStreamingEngineSession();
    }

    private void releaseStreamingEngineSession() {
        AsrEngine.StreamingSession session = streamingEngineSession;
        streamingEngineSession = null;
        if (session != null && asrReady.getAsBoolean()) {
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

    private void publishFinalText(String text, String rawText, int turnId, long sessionId, String inputMode, boolean wakeWordMatched) {
        protocolAdapter.publishFinalText(new AsrTextPayload(text, rawText, turnId, sessionId, inputMode, wakeWordMatched, System.currentTimeMillis()));
    }

    private void handleInterruptEvent(InterruptEvent interruptEvent) {
        env.info("ASR Worker 收到打断事件，sessionId=" + interruptEvent.getSessionId());
        audioQueue.clear();
        streamingSessionId.set(interruptEvent.getSessionId());
        if (isStreaming && asrReady.getAsBoolean()) {
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
