package com.rheinmetal.tianshu.event;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TianshuEventBus {

    private final BlockingQueue<TianshuEvent> asrQueue;
    private final BlockingQueue<TianshuEvent> llmQueue;
    private final BlockingQueue<TianshuEvent> ttsQueue;
    private final BlockingQueue<TianshuEvent> uiQueue;
    private final IGameEnvironment env;

    public TianshuEventBus(IGameEnvironment env) {
        this.env = env;
        this.asrQueue = new LinkedBlockingQueue<>();
        this.llmQueue = new LinkedBlockingQueue<>();
        this.ttsQueue = new LinkedBlockingQueue<>();
        this.uiQueue = new LinkedBlockingQueue<>();
        env.info("天枢事件总线初始化完成");
    }

    public void publishEvent(TianshuEvent event) {
        try {
            if (event instanceof AsrPartialTextEvent) {
                uiQueue.put(event);
            } else if (event instanceof AsrFinalTextEvent) {
                llmQueue.put(event);
                uiQueue.put(event);
            } else if (event instanceof LlmChunkEvent) {
                ttsQueue.put(event);
                uiQueue.put(event);
            } else if (event instanceof LlmEndEvent) {
                ttsQueue.put(event);
                uiQueue.put(event);
            } else if (event instanceof TtsAudioEvent) {
                // TTS audio is played directly, no queue needed
            } else if (event instanceof InterruptEvent) {
                asrQueue.put(event);
                llmQueue.put(event);
            } else if (event instanceof StartListeningEvent ||
                       event instanceof StopListeningEvent ||
                       event instanceof StartStreamRecordingEvent ||
                       event instanceof StopStreamRecordingEvent ||
                       event instanceof ForceAsrFlushEvent) {
                asrQueue.put(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env.error("发布事件失败", e);
        }
    }

    public BlockingQueue<TianshuEvent> getAsrQueue() {
        return asrQueue;
    }

    public BlockingQueue<TianshuEvent> getLlmQueue() {
        return llmQueue;
    }

    public BlockingQueue<TianshuEvent> getTtsQueue() {
        return ttsQueue;
    }

    public BlockingQueue<TianshuEvent> getUiQueue() {
        return uiQueue;
    }

    public void clearAllQueues() {
        asrQueue.clear();
        llmQueue.clear();
        ttsQueue.clear();
        uiQueue.clear();
    }
}
