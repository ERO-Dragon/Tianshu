package com.rheinmetal.tianshu.event;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class TianshuEventBus {

    private final BlockingQueue<TianshuEvent> asrQueue;
    private final BlockingQueue<TianshuEvent> uiQueue;
    private final IGameEnvironment env;
    private final AtomicLong sessionSeq = new AtomicLong(1);
    private volatile long activeSessionId;

    public TianshuEventBus(IGameEnvironment env) {
        this.env = env;
        this.asrQueue = new LinkedBlockingQueue<>();
        this.uiQueue = new LinkedBlockingQueue<>();
        this.activeSessionId = sessionSeq.get();
        env.info("天枢事件总线初始化完成");
    }

    public long getActiveSessionId() {
        return activeSessionId;
    }

    public long beginNewSession() {
        long next = sessionSeq.incrementAndGet();
        activeSessionId = next;
        return next;
    }

    public boolean isCurrentSession(long sessionId) {
        return sessionId == 0L || sessionId == activeSessionId;
    }

    public void publishEvent(TianshuEvent event) {
        if (event == null) return;
        try {
            if (event instanceof InterruptEvent) {
                asrQueue.put(event);
                uiQueue.put(event);
                return;
            }

            if (!isCurrentSession(event.getSessionId())) {
                env.info("丢弃过期事件，sessionId=" + event.getSessionId() + ", activeSessionId=" + activeSessionId);
                return;
            }

            if (event instanceof AsrPartialTextEvent) {
                uiQueue.put(event);
            } else if (event instanceof UiAsrTextEvent) {
                uiQueue.put(event);
            } else if (event instanceof UiLlmTextEvent) {
                uiQueue.put(event);
            } else if (event instanceof UiLlmEndEvent) {
                uiQueue.put(event);
            } else if (event instanceof TtsAudioEvent) {
            } else if (event instanceof TtsPlaybackEndEvent) {
                uiQueue.put(event);
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

    public BlockingQueue<TianshuEvent> getUiQueue() {
        return uiQueue;
    }

    public void clearAllQueues() {
        asrQueue.clear();
        uiQueue.clear();
    }

    public long interruptLlmAndTts() {
        long nextSession = beginNewSession();
        uiQueue.offer(new InterruptEvent(nextSession));
        return nextSession;
    }
}
