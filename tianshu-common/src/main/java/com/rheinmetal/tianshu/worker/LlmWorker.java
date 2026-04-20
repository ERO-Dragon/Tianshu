package com.rheinmetal.tianshu.worker;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.Engine.LlmEngine;
import com.rheinmetal.tianshu.event.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmWorker implements Runnable {
    private final LlmEngine llmEngine;
    private final TianshuCoreManager coreManager;
    private final IGameEnvironment env;
    private final BlockingQueue<TianshuEvent> llmQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);

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

                if (event instanceof InterruptEvent) {
                    handleInterruptEvent();
                    continue;
                }

                if (event instanceof AsrFinalTextEvent asrEvent) {
                    if (!coreManager.getState().isLlmReady()) {
                        env.info("LLM 尚未就绪，跳过请求");
                        continue;
                    }

                    int turnId = asrEvent.getTurnId();
                    if (turnId < currentTurnId.get()) {
                        env.info("LLM Worker 丢弃过期事件，turnId: " + turnId);
                        continue;
                    }

                    llmEngine.cancelGeneration();
                    currentTurnId.set(turnId);
                    env.info("LLM Worker 开始处理，turnId: " + turnId);
                    var prompt = asrEvent.getText() + "/no_think";
                    llmEngine.streamChat(prompt, textChunk -> {
                        coreManager.getEventBus().publishEvent(new LlmChunkEvent(textChunk, turnId));
                    }, () -> {
                        coreManager.getEventBus().publishEvent(new LlmEndEvent(turnId));
                        env.info("LLM Worker 处理完成，turnId: " + turnId);
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

    public void stop() {
        running = false;
        llmQueue.clear();
        try {
            llmQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (llmEngine != null) {
            llmEngine.shutdown();
        }
    }

    private void handleInterruptEvent() {
        env.info("LLM Worker 收到打断事件");
        currentTurnId.incrementAndGet();
        llmEngine.cancelGeneration();
    }
}
