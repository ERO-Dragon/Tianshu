package com.rheinmetal.tianshu.worker;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.LlmEngine;
import com.rheinmetal.tianshu.event.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LlmWorker implements Runnable {
    private final LlmEngine llmEngine;
    private final TianshuCoreManager coreManager;
    private final IGameEnvironment env;
    private final BlockingQueue<TianshuEvent> llmQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);
    private final AtomicLong currentSessionId = new AtomicLong(0L);

    public LlmWorker(TianshuCoreManager coreManager, IGameEnvironment env, ITianshuConfig config) {
        this.coreManager = coreManager;
        this.env = env;
        this.llmEngine = new LlmEngine(env, "http://127.0.0.1:" + config.getLlmPort());
        this.llmQueue = coreManager.getEventBus().getLlmQueue();
    }

    @Override
    public void run() {
        env.info("LLM Worker 启动");

        try {
            while (running) {
                TianshuEvent event = llmQueue.take();

                if (event instanceof InterruptEvent interruptEvent) {
                    handleInterruptEvent(interruptEvent);
                    continue;
                }

                if (event instanceof AsrFinalTextEvent asrEvent) {
                    if (!coreManager.getState().isLlmReady()) {
                        env.info("LLM 尚未就绪，跳过请求");
                        coreManager.getEventBus().publishEvent(new LlmEndEvent(asrEvent.getTurnId(), asrEvent.getSessionId(), false, "LLM 尚未就绪"));
                        continue;
                    }

                    int turnId = asrEvent.getTurnId();
                    long sessionId = asrEvent.getSessionId();
                    if (turnId < currentTurnId.get()) {
                        env.info("LLM Worker 丢弃过期事件，turnId: " + turnId);
                        continue;
                    }
                    if (!coreManager.getEventBus().isCurrentSession(sessionId)) {
                        env.info("LLM Worker 丢弃过期会话，sessionId: " + sessionId);
                        continue;
                    }

                    llmEngine.cancelGeneration();
                    currentTurnId.set(turnId);
                    currentSessionId.set(sessionId);
                    env.info("LLM Worker 开始处理，turnId: " + turnId + ", sessionId=" + sessionId);
                    String prompt = asrEvent.getText() + "\n/no_think";
                    llmEngine.streamChat(prompt, textChunk -> {
                        if (!isCurrent(turnId, sessionId)) {
                            return;
                        }
                        coreManager.getEventBus().publishEvent(new LlmChunkEvent(textChunk, turnId, sessionId));
                    }, finishReason -> {
                        if (!coreManager.getEventBus().isCurrentSession(sessionId)) {
                            return;
                        }
                        boolean cancelled = finishReason == LlmEngine.FinishReason.CANCELLED;
                        String errorMessage = finishReason == LlmEngine.FinishReason.FAILED ? "LLM 请求失败" : null;
                        coreManager.getEventBus().publishEvent(new LlmEndEvent(turnId, sessionId, cancelled, errorMessage));
                        env.info("LLM Worker 处理结束，turnId: " + turnId + ", sessionId=" + sessionId + ", finishReason=" + finishReason);
                    }, errorMessage -> {
                        if (!coreManager.getEventBus().isCurrentSession(sessionId)) {
                            return;
                        }
                        coreManager.getEventBus().publishEvent(new LlmEndEvent(turnId, sessionId, false, errorMessage));
                    });
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env.info("LLM Worker 被中断");
        } catch (Exception e) {
            env.error("LLM Worker 发生错误", e);
        } finally {
            env.info("LLM Worker 停止");
        }
    }

    private boolean isCurrent(int turnId, long sessionId) {
        return turnId >= currentTurnId.get() && sessionId == currentSessionId.get() && coreManager.getEventBus().isCurrentSession(sessionId);
    }

    public void stop() {
        running = false;
        llmQueue.clear();
        try {
            llmQueue.put(new InterruptEvent(currentSessionId.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        llmEngine.shutdown();
    }

    private void handleInterruptEvent(InterruptEvent interruptEvent) {
        env.info("LLM Worker 收到打断事件，sessionId=" + interruptEvent.getSessionId());
        currentSessionId.set(interruptEvent.getSessionId());
        llmEngine.cancelGeneration();
    }
}
