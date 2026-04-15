package com.rheinmetal.tianshu.core.workers;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.TianshuEventBus;
import com.rheinmetal.tianshu.core.engine.AsrEngine;
import com.rheinmetal.tianshu.core.events.*;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class AsrWorker implements Runnable {
    private final AudioManager audioManager;
    private final TianshuEventBus eventBus;
    private final BlockingQueue<byte[]> audioQueue;
    private final BlockingQueue<TianshuEvent> asrQueue;
    private boolean running = true;
    private final AtomicInteger turnId = new AtomicInteger(0);
    private boolean isStreaming = false;

    public AsrWorker(AudioManager audioManager) {
        this.audioManager = audioManager;
        this.eventBus = TianshuEventBus.getInstance();
        this.audioQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        this.asrQueue = eventBus.getAsrQueue();
    }

    private AsrEngine getAsrEngine() {
        return TianshuCoreManager.getInstance().getAsrEngine();
    }

    @Override
    public void run() {
        Tianshu.LOGGER.info("ASR Worker 启动，进入待机状态");

        try {
            while (running) {
                // 阻塞等待事件
                TianshuEvent event = asrQueue.take();

                if (event instanceof InterruptEvent) {
                    // 处理打断事件
                    handleInterruptEvent();
                } else if (event instanceof StartListeningEvent) {
                    // 处理PTT模式开始录音
                    handleStartListening();
                } else if (event instanceof StopListeningEvent) {
                    // 处理PTT模式停止录音
                    handleStopListening();
                } else if (event instanceof StartStreamRecordingEvent) {
                    // 处理常开模式开始流式录音
                    handleStartStreamRecording();
                } else if (event instanceof StopStreamRecordingEvent) {
                    // 处理常开模式停止流式录音
                    handleStopStreamRecording();
                }else if (event instanceof ForceAsrFlushEvent) {
                    handleForceFlush();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Tianshu.LOGGER.info("ASR Worker 被中断");
        } catch (Exception e) {
            Tianshu.LOGGER.error("ASR Worker 发生错误", e);
        } finally {
            // 清理资源
            cleanup();
            Tianshu.LOGGER.info("ASR Worker 停止");
        }
    }

    // 处理PTT模式开始录音
    private void handleStartListening() {
        if (!TianshuCoreManager.getInstance().isEngineReady()) {
            Tianshu.LOGGER.warn("引擎未就绪，跳过录音");
            return;
        }
        isStreaming = false;
        audioManager.stopStreamRecording();

        Tianshu.LOGGER.info("ASR Worker 开始PTT录音");
// 【测试代码开始】
Minecraft.getInstance().execute(() -> {
    if (Minecraft.getInstance().player != null) {
        Minecraft.getInstance().player.displayClientMessage(
            Component.literal("§a[天枢调试] §f检测到V键按下，开始录音..."), false
        );
    }
});
// 【测试代码结束】
        audioManager.startRecording();
    }

    // 处理PTT模式停止录音
     private void handleStopListening() {
        if (!TianshuCoreManager.getInstance().isEngineReady()) {
            Tianshu.LOGGER.warn("引擎未就绪，跳过录音停止");
            return;
        }
        Tianshu.LOGGER.info("ASR Worker 停止PTT录音");
        byte[] audioData = audioManager.stopRecording();
        if (audioData != null && audioData.length > 0) {
            // 识别完整音频
            String result = getAsrEngine().recognizeComplete(audioData);
            if (!result.isEmpty()) {
                // PTT模式下，玩家按键就是明确要对话，直接发送，不做任何热词拦截
                int currentTurnId = turnId.incrementAndGet();
                eventBus.publishEvent(new AsrFinalTextEvent(result, currentTurnId));
                Tianshu.LOGGER.info("ASR 识别完成，turnId: {}", currentTurnId);
                
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§d[天枢ASR测试] §f" + result), false
                        );
                    }
                });
            }
        }
    }
    // 处理按键强制截断
    private void handleForceFlush() {
        if (!TianshuCoreManager.getInstance().isEngineReady()) return;
        Tianshu.LOGGER.info("ASR Worker 收到强制截断指令");
        // 【关键新增】清空 Java 音频队列里的积压数据，防止截断后的残音被当成新句子
        audioQueue.clear();
        String result = getAsrEngine().forceFlush();
        if (isMeaningfulText(result)) {
            int currentTurnId = turnId.incrementAndGet();
            // 直接发给 LLM
            eventBus.publishEvent(new AsrFinalTextEvent(result, currentTurnId));
            Tianshu.LOGGER.info("ASR 强制截断识别完成，turnId: {}", currentTurnId);
// 【测试代码开始】
Minecraft.getInstance().execute(() -> {
    if (Minecraft.getInstance().player != null) {
        Minecraft.getInstance().player.displayClientMessage(
            Component.literal("§d[天枢ASR测试] §f" + result), false
        );
    }
});
// 【测试代码结束】
        }
    }
    // 处理常开模式开始流式录音
    private void handleStartStreamRecording() {
        if (!TianshuCoreManager.getInstance().isEngineReady()) {
            Tianshu.LOGGER.warn("引擎未就绪，跳过流式录音启动");
            return;
        }
        if (isStreaming) {
            return;
        }

        Tianshu.LOGGER.info("ASR Worker 开始流式录音");
        isStreaming = true;

// 【测试代码开始】
Minecraft.getInstance().execute(() -> {
    if (Minecraft.getInstance().player != null) {
        Minecraft.getInstance().player.displayClientMessage(
            Component.literal("§a[天枢调试] §f常开/热词模式已启动，请说话..."), false
        );
    }
});
// 【测试代码结束】
        // 创建流式处理对象
        getAsrEngine().createStream();

        // 启动流式录音
        audioManager.startStreamRecording(chunk -> {
            try {
                audioQueue.put(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 启动音频数据处理线程
        Thread audioProcessor = new Thread(() -> {
            try {
                while (isStreaming) {
                    // 从队列中获取音频数据
                    byte[] chunk = audioQueue.take();
                    // 处理音频数据
                    String text = getAsrEngine().feedAudio(chunk);
                    if (!text.isEmpty()) {
                        // 发布ASR中间结果事件
                        eventBus.publishEvent(new AsrPartialTextEvent(text));
                    }
                    // 检查是否达到端点
                    if (getAsrEngine().isEndpoint()) {
                        // text 就是最终结果，不需要再去喂空数组
                        if (isMeaningfulText(text)) {
                            // 检查是否是热词模式
                            if (isWakeWordMode()) {
                                String wakeWord = Config.WAKE_WORD.get();
                                if (text.contains(wakeWord)) {
                                    int currentTurnId = turnId.incrementAndGet();
                                    int index = text.indexOf(wakeWord) + wakeWord.length();
                                    String realCommand = text.substring(index).trim();
                                    if (isMeaningfulText(realCommand)) {
                                        eventBus.publishEvent(new AsrFinalTextEvent(realCommand, currentTurnId));
                                    }
                                } else {
                                    Tianshu.LOGGER.info("ASR 断句完成，未命中唤醒词: {}", wakeWord);
                                }
                            } else {
                                int currentTurnId = turnId.incrementAndGet();

    String finalText = text; // lambda 需要 effectively final
    Minecraft.getInstance().execute(() -> {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("§d[天枢ASR测试] §f" + finalText), false
            );
        }
    });



                                eventBus.publishEvent(new AsrFinalTextEvent(text, currentTurnId));
                                Tianshu.LOGGER.info("ASR 断句完成，turnId: {}", currentTurnId);
                            }
                        }
                        // 重置ASR引擎，准备听下一句
                        getAsrEngine().reset();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ASR-Audio-Processor");
        audioProcessor.setDaemon(true); // 必须加这行！
        audioProcessor.start();
    }

    // 处理常开模式停止流式录音
    private void handleStopStreamRecording() {

        Tianshu.LOGGER.info("ASR Worker 收到停止流式指令，强制清理底层");
        isStreaming = false;    
        if (TianshuCoreManager.getInstance().isEngineReady()) {
        getAsrEngine().reset();
    }
        audioManager.stopStreamRecording(); 
        audioQueue.clear();
    }

    // 检查是否是热词模式
    private boolean isWakeWordMode() {
        return Config.TRIGGER_MODE.get() == Config.TriggerMode.WAKE_WORD;
    }
    // 判断识别出来的文本是否有意义（过滤单个标点、单字等）
    private boolean isMeaningfulText(String text) {
        if (text == null || text.isEmpty()) return false;
        // 正则魔法：\p{P}匹配任何标点符号，\s匹配空白字符，\p{C}匹配控制字符
        String cleanText = text.replaceAll("[\\p{P}\\s\\p{C}]", "");
        // 剔除标点后，有效字符至少要 >= 2 个（比如"你好"、"嗯啊"允许，但"啊"、"。"直接丢弃）
        return cleanText.length() >= 2;
    }
    // 处理打断事件
    private void handleInterruptEvent() {
        Tianshu.LOGGER.info("ASR Worker 收到打断事件");
        // 重置turnId
        turnId.incrementAndGet(); // 增加turnId而不是重置为0
        // 清理音频队列
        audioQueue.clear();
        if (isStreaming && TianshuCoreManager.getInstance().isEngineReady()) {
            getAsrEngine().reset();
        }
    }

    // 清理资源
    private void cleanup() {
        if (isStreaming) {
            handleStopStreamRecording();
        }
        audioManager.stopRecording();
        audioQueue.clear();
    }

    // 停止Worker
    public void stop() {
        running = false;
        isStreaming = false;
        // 清理音频队列，让take()方法返回
        audioQueue.clear();
        // 添加一个事件到队列，触发take()方法返回
        try {
            asrQueue.put(new InterruptEvent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}