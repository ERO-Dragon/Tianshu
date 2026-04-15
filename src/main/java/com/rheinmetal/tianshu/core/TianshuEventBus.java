package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.core.events.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TianshuEventBus {
    // 单例模式
    private static TianshuEventBus instance;

    // 各个Worker的事件队列
    private final BlockingQueue<TianshuEvent> asrQueue;
    private final BlockingQueue<TianshuEvent> llmQueue;
    private final BlockingQueue<TianshuEvent> ttsQueue;
    private final BlockingQueue<TianshuEvent> uiQueue;

    private TianshuEventBus() {
        // 初始化事件队列
        this.asrQueue = new LinkedBlockingQueue<>();
        this.llmQueue = new LinkedBlockingQueue<>();
        this.ttsQueue = new LinkedBlockingQueue<>();
        this.uiQueue = new LinkedBlockingQueue<>();

        Tianshu.LOGGER.info("天枢事件总线初始化完成");
    }

    // 获取单例
    public static synchronized TianshuEventBus getInstance() {
        if (instance == null) {
            instance = new TianshuEventBus();
        }
        return instance;
    }

    // 发布事件
    public void publishEvent(TianshuEvent event) {
        try {
            if (event instanceof AsrPartialTextEvent) {
                // ASR中间结果只投递到UI队列
                uiQueue.put(event);
            } else if (event instanceof AsrFinalTextEvent) {
                // ASR最终结果只投递到LLM队列
                llmQueue.put(event);
            } else if (event instanceof LlmChunkEvent) {
                // LLM文本块只投递到TTS队列
                ttsQueue.put(event);
    uiQueue.put(event); // 【新增】抄送给 UI
            } else if (event instanceof LlmEndEvent) {
                // LLM结束事件投递到TTS队列
                ttsQueue.put(event);
    uiQueue.put(event); // 【新增】抄送给 UI
            } else if (event instanceof TtsAudioEvent) {
                // TTS音频事件投递到ASR队列（如果需要的话）
                // 这里暂时不需要，TTS音频直接播放
            } else if (event instanceof InterruptEvent) {
                // 打断事件投递到所有队列
                asrQueue.put(event);
                llmQueue.put(event);
            } else if (event instanceof StartListeningEvent || 
                       event instanceof StopListeningEvent || 
                       event instanceof StartStreamRecordingEvent || 
                       event instanceof StopStreamRecordingEvent || 
                       event instanceof ForceAsrFlushEvent) {
                // 控制事件只投递到ASR队列
                asrQueue.put(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Tianshu.LOGGER.error("发布事件失败", e);
        }
    }

    // 获取ASR队列
    public BlockingQueue<TianshuEvent> getAsrQueue() {
        return asrQueue;
    }

    // 获取LLM队列
    public BlockingQueue<TianshuEvent> getLlmQueue() {
        return llmQueue;
    }

    // 获取TTS队列
    public BlockingQueue<TianshuEvent> getTtsQueue() {
        return ttsQueue;
    }

    // 获取UI队列
    public BlockingQueue<TianshuEvent> getUiQueue() {
        return uiQueue;
    }

    // 清空所有队列
    public void clearAllQueues() {
        asrQueue.clear();
        llmQueue.clear();
        ttsQueue.clear();
        uiQueue.clear();
        Tianshu.LOGGER.info("所有事件队列已清空");
    }
}