package com.rheinmetal.tianshu.core.workers;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.core.TianshuEventBus;
import com.rheinmetal.tianshu.core.engine.LlmEngine;
import com.rheinmetal.tianshu.core.events.AsrFinalTextEvent;
import com.rheinmetal.tianshu.core.events.InterruptEvent;
import com.rheinmetal.tianshu.core.events.LlmChunkEvent;
import com.rheinmetal.tianshu.core.events.LlmEndEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class LlmWorker implements Runnable {
    private final LlmEngine llmEngine;
    private final TianshuEventBus eventBus;
    private final BlockingQueue<com.rheinmetal.tianshu.core.events.TianshuEvent> llmQueue;
    private boolean running = true;
    private final AtomicInteger currentTurnId = new AtomicInteger(0);

    public LlmWorker() {
        // Worker 自己管理 LlmEngine 的生命周期
        this.llmEngine = new LlmEngine("http://127.0.0.1:" + Config.LLM_PORT.get());
        this.eventBus = TianshuEventBus.getInstance();
        this.llmQueue = eventBus.getLlmQueue();
    }

    @Override
    public void run() {
        Tianshu.LOGGER.info("LLM Worker 启动");

        try {
            while (running) {
                // 阻塞监听队列
                com.rheinmetal.tianshu.core.events.TianshuEvent event = llmQueue.take();

                if (event instanceof InterruptEvent) {
                    // 处理打断事件
                    handleInterruptEvent();
                    continue;
                }

                if (event instanceof AsrFinalTextEvent asrEvent) {
                    // 【新增】防御拦截：如果 LLM 还没拉起，友好提示并直接跳过，不浪费 turnId
                    if (!com.rheinmetal.tianshu.client.TianshuClient.llmReady) {
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                                net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal("§c[天枢] §f中枢核心正在苏醒，请稍等片刻"), false
                                );
                            }
                        });
                        continue; // 关键：直接 continue，不增加 turnId，不调接口
                    }
                    // 处理ASR最终结果事件
                    int turnId = asrEvent.getTurnId();
                    // 检查turnId是否过期
                    if (turnId < currentTurnId.get()) {
                        Tianshu.LOGGER.info("LLM Worker 丢弃过期事件，turnId: {}", turnId);
                        continue;
                    }

                    llmEngine.cancelGeneration();
                    // 更新当前turnId
                    currentTurnId.set(turnId);
                    Tianshu.LOGGER.info("LLM Worker 开始处理，turnId: {}", turnId);

                    // 调用LLM服务生成回复
                    llmEngine.streamChat(asrEvent.getText(), textChunk -> {
                        // 发布LLM文本块事件
                        eventBus.publishEvent(new LlmChunkEvent(textChunk, turnId));
                    }, () -> {
                        // 发布LLM结束事件
                        eventBus.publishEvent(new LlmEndEvent(turnId));
                        Tianshu.LOGGER.info("LLM Worker 处理完成，turnId: {}", turnId);
                    });
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Tianshu.LOGGER.info("LLM Worker 被中断");
        } catch (Exception e) {
            Tianshu.LOGGER.error("LLM Worker 发生错误", e);
        } finally {
            Tianshu.LOGGER.info("LLM Worker 停止");
        }
    }

    // 停止Worker
    public void stop() {
        running = false;
        // 清理队列，让take()方法返回
        llmQueue.clear();
        // 添加一个事件到队列，触发take()方法返回
        try {
            llmQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关闭 LlmEngine
        if (llmEngine != null) {
            llmEngine.shutdown();
        }
    }

    // 处理打断事件
    private void handleInterruptEvent() {
        Tianshu.LOGGER.info("LLM Worker 收到打断事件");
        // 增加turnId而不是重置为0
        currentTurnId.incrementAndGet();
        // 取消当前的LLM请求
        llmEngine.cancelGeneration();
    }
}
