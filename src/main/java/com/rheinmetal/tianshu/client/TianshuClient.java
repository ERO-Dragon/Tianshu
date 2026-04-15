package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rheinmetal.tianshu.Tianshu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import com.rheinmetal.tianshu.core.ProcessManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.TianshuThreadPool;
import com.rheinmetal.tianshu.core.events.LlmChunkEvent;
import com.rheinmetal.tianshu.core.events.LlmEndEvent;
import com.rheinmetal.tianshu.core.events.TianshuEvent;
import com.rheinmetal.tianshu.core.TianshuEventBus;
import com.rheinmetal.tianshu.core.workers.AsrWorker;
import com.rheinmetal.tianshu.core.workers.LlmWorker;
import com.rheinmetal.tianshu.core.workers.TtsWorker;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.gui.TianshuGUI;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.audio.AudioManager;
import org.lwjgl.glfw.GLFW;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;


public class TianshuClient {

    public static KeyMapping VOICE_KEY; // 动态更新的按键映射
    private static boolean wasAlwaysKeyTriggered = false;


    // 初始化VOICE_KEY
    public static void initVoiceKey() {
        VOICE_KEY = new KeyMapping(
                "key.tianshu.activate", // 翻译键（后面要配语言文件）
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.tianshu" // 按键分类名
        );
    }
    private static boolean isVoiceKeyPressed = false;

    // 核心组件
    private static ProcessManager processManager;
    private static AudioManager audioManager;
    private static ModelManager modelManager;
    
    // 新架构组件
    private static TianshuThreadPool threadPool;
    private static TianshuEventBus eventBus;
    private static AsrWorker asrWorker;
    private static LlmWorker llmWorker;
    private static TtsWorker ttsWorker;

    public static boolean asrReady = false;
    public static boolean llmReady = false;
    public static boolean ttsReady = false;
    // 加载标记
    private static boolean initialized = false;
    private static Config.TriggerMode lastTriggerMode = null;
    // Debug: 当前 LLM 回复
    private static final StringBuilder currentLlmReply = new StringBuilder();
    /**
     * 由主类 Tianshu.java 调用，用来挂载游戏内的事件监听
     */
    public static void init() {
        Tianshu.LOGGER.info("天枢 AI 客户端事件开始注册...");

        // 初始化 TianshuCoreManager
        TianshuCoreManager.getInstance();
        
        // 【新增】游戏刚启动（主菜单），直接长驻劫持麦克风，避免任何业务操作触发Windows音量波动
        audioManager = new AudioManager();
        audioManager.ensureHardwareRunning();

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);
        // 【修复】使用纯客户端的事件，不要去管 isClientSide
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> {
            Tianshu.LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
            TianshuCoreManager.getInstance().tryInitOnWorldJoined();
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
            Tianshu.LOGGER.info("检测到客户端退出世界，开始清理...");
            shutdownClient(); // 停掉 Worker 和队列
            TianshuCoreManager.getInstance().destroyOnWorldLeft(); // 通知大管家停掉引擎
        });
        // 【新增】注册 JVM 关机钩子，确保游戏退出时杀掉 LLM 子进程，释放显存
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Tianshu.LOGGER.info("检测到 JVM 即将关闭，执行最终清理...");
            if (processManager != null) processManager.stopServices();
            if (audioManager != null) audioManager.shutdown(); // 【新增】游戏彻底退出时才释放麦克风
        }, "Tianshu-Shutdown-Hook"));

    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        initVoiceKey(); // 初始化按键映射
        event.register(VOICE_KEY);
    }

    private static synchronized void ensureInitialized() {
        if (!initialized) {
            // 检查环境是否就绪
            if (!com.rheinmetal.tianshu.core.EnvSetupManager.isEnvironmentReady()) {
                Tianshu.LOGGER.info("环境未就绪，跳过核心组件初始化");
                return;
            }
            
            try {
                Tianshu.LOGGER.info("首次触发 AI，开始加载核心控制组件...");
                processManager = new ProcessManager();
                // 前面已经创建了
                // audioManager = new AudioManager();
                modelManager = new ModelManager();
                
                // 初始化新架构组件
                threadPool = TianshuThreadPool.getInstance();
                eventBus = TianshuEventBus.getInstance();
                
                // 初始化Workers
                asrWorker = new AsrWorker(audioManager);
                llmWorker = new LlmWorker();
                ttsWorker = new TtsWorker(audioManager);
                
                // 启动Workers
                threadPool.getAsrWorker().execute(asrWorker);
                threadPool.getLlmWorker().execute(llmWorker);
                threadPool.getTtsWorker().execute(ttsWorker);
                
                // 使用独立后台线程启动LLM服务器，避免阻塞业务线程
                new Thread(() -> processManager.startLlmServerForDev(), "LLM-Process-Starter").start();
                
                initialized = true;
                Tianshu.LOGGER.info("天枢核心控制组件加载完成！");
            } catch (Exception e) {
                Tianshu.LOGGER.error("天枢核心控制组件初始化失败", e);
                initialized = false; // 失败允许下次重试
            }
        }
    }

    public static ModelManager getModelManager() {
        ensureInitialized();
        return modelManager;
    }

    public static ProcessManager getProcessManager() {
        ensureInitialized();
        return processManager;
    }

    @SubscribeEvent
    public static void onClientTick(PlayerTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        if (!Config.AI_ENABLED.get()) {
        if (isVoiceKeyPressed) {
            isVoiceKeyPressed = false;
            eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopStreamRecordingEvent());
            eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopListeningEvent());
            lastTriggerMode = null;
        }
        return;
    }

        handleVoiceKey();
    }

    private static void handleVoiceKey() {
        // 确保核心组件已初始化
        ensureInitialized();

        // 检查引擎是否就绪
        if (!TianshuCoreManager.getInstance().isEngineReady()) {
            return;
        }

        Config.TriggerMode currentMode = Config.TRIGGER_MODE.get();

        // 【核心修复】：如果模式发生了改变，必须先彻底清理上一个模式的状态！
        if (lastTriggerMode != null && lastTriggerMode != currentMode) {
            Tianshu.LOGGER.info("检测到模式切换: {} -> {}, 执行全局清理", lastTriggerMode, currentMode);
            // 无论之前是什么，统统发停止事件，Worker内部会做防御性判断
            eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopStreamRecordingEvent());
            eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopListeningEvent());
            isVoiceKeyPressed = false; // 重置按键状态
            wasAlwaysKeyTriggered = false; // 重置截断状态
        }
        switch (Config.TRIGGER_MODE.get()) {
            case ALWAYS -> {
                // 常开模式：持续流式监听
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    startAlwaysOnMode();
                }
                boolean isDown = VOICE_KEY.isDown();
                if (isDown && !wasAlwaysKeyTriggered) {
                    wasAlwaysKeyTriggered = true;
                    // 发送截断事件，ASR 会立刻算出当前说的话并发给 LLM
                    eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.ForceAsrFlushEvent());
                } else if (!isDown) {
                    // 按键抬起后重置标记，准备下一次截断
                    wasAlwaysKeyTriggered = false;
                }
            }
            case PUSH_TO_TALK -> {
                // 原PTT模式：按键说话，松开发送
                boolean isTriggered = VOICE_KEY.isDown();
                if (isTriggered && !isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    // 发送开始录音事件
                    eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StartListeningEvent());
                } else if (!isTriggered && isVoiceKeyPressed) {
                    isVoiceKeyPressed = false;
                    // 发送停止录音事件
                    eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopListeningEvent());
                }
            }
            case WAKE_WORD -> {
                // 热词模式：持续监听，检测到热词才响应
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    startWakeWordMode();
                }
            }
        }
    }
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof PauseScreen) {
            int screenWidth = screen.width;
            int buttonWidth = 200;
            int buttonHeight = 20;
            int buttonX = (screenWidth - buttonWidth) / 2;

            // 动态寻找最底部的锚点
            int maxY = 0;
            for (GuiEventListener widget : screen.children()) {
                if (widget instanceof Button button) {
                    // 【关键】要加上按钮的高度，拿到真正的"底边"
                    int buttonBottomY = button.getY() + button.getHeight();
                    if (buttonBottomY > maxY) {
                        maxY = buttonBottomY;
                    }
                }
            }
            // 贴在最底部按钮下方，留 5 像素间距
            int myButtonY = maxY + 5;

            event.addListener(Button.builder(
                    Component.literal("天枢 AI 控制台"),
                    (button) -> Minecraft.getInstance().setScreen(new TianshuGUI())
            ).pos(buttonX, myButtonY).size(buttonWidth, buttonHeight).build());
        }
//        else if (screen instanceof OptionsScreen) {
//            // ===== 2. 选项面板逻辑（融入原版左列） =====
//
//            // 【关键1】直接使用原版源码里的硬核公式！宽度也改成和原版一样的 150，绝对不歪！
//            int buttonWidth = 400;
//            int buttonHeight = 20;
//            int myButtonX = (screen.width - buttonWidth) / 2;
//
//            // 【关键2】兜底 Y 坐标（防止极端情况找不到按钮）
//            int myButtonY = screen.height - 50;
//
//            // 【关键3】只抓取"完成"按钮，因为它是锚点，最稳定不会变
//            Component doneText = Component.translatable("gui.done");
//            for (GuiEventListener widget : screen.children()) {
//                if (widget instanceof Button button) {
//                    if (!button.getMessage().equals(doneText)) {
//                        // 找到完成按钮，贴在它正下方，留 5 像素间距
//                        myButtonY = button.getY() + button.getHeight() + 5;
//                        break;
//                    }
//                }
//            }
//
//            event.addListener(Button.builder(
//                    Component.literal("天枢 AI 控制台"),
//                    (button) -> Minecraft.getInstance().setScreen(new TianshuGUI())
//            ).pos(myButtonX, myButtonY).size(buttonWidth, buttonHeight).build());
//        }
    }

    // 启动常开模式
    private static void startAlwaysOnMode() {
        // 发送开始流式录音事件
        eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StartStreamRecordingEvent());
        Tianshu.LOGGER.info("启动常开模式");
    }

    // 启动热词模式
    private static void startWakeWordMode() {
        // 发送开始流式录音事件
        eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StartStreamRecordingEvent());
        Tianshu.LOGGER.info("启动热词模式");
    }

    // 停止流式模式
    private static void stopStreamingMode() {
        // 发送停止流式录音事件
        eventBus.publishEvent(new com.rheinmetal.tianshu.core.events.StopStreamRecordingEvent());
        Tianshu.LOGGER.info("停止流式模式");
    }
    // 清理资源
    public static void shutdownClient() {
        Tianshu.LOGGER.info("关闭天枢客户端资源");
        
        // 停止Worker
        if (asrWorker != null) asrWorker.stop();
        if (llmWorker != null) llmWorker.stop();
        if (ttsWorker != null) ttsWorker.stop();
        
        // 关闭原有组件
        if (processManager != null) processManager.stopServices();
        if (audioManager != null) {
            audioManager.stopRecording();
            audioManager.stopStreamRecording();
        }
        
        // 清空事件队列
        if (eventBus != null) eventBus.clearAllQueues();
        
        initialized = false;
        isVoiceKeyPressed = false;
        lastTriggerMode = null;
        
        asrReady = false;
        llmReady = false;
        ttsReady = false;
        Tianshu.LOGGER.info("天枢客户端资源清理完成");
    }
    //Debug方法
    
    public static void registerOverlays(RegisterGuiLayersEvent event) {
        event.registerAbove(
            ResourceLocation.withDefaultNamespace("chat"), // 锚点：原版聊天框
            ResourceLocation.fromNamespaceAndPath("tianshu", "llm_reply"), // 我们图层的ID
            TianshuClient::renderLlmReply // 实际画画的函数
        );
    }
    public static void renderLlmReply(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (eventBus == null) return;
        // 在里面自己获取屏幕高度
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();        // 1. 疯狂消费 UI 队列里的事件
        while (!eventBus.getUiQueue().isEmpty()) {
            TianshuEvent e = eventBus.getUiQueue().poll();
            if (e instanceof LlmChunkEvent chunk) {
                // 收到一个字，拼接到字符串里
                currentLlmReply.append(chunk.getText());
            } else if (e instanceof LlmEndEvent) {
                // 收到结束信号，把完整的一句话沉淀到聊天历史记录里
                if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§b[天枢] §f" + currentLlmReply.toString()), false
                    );
                    // 清空缓冲区，准备下一句
                    currentLlmReply.setLength(0);
                }
            }
        }

        // 2. 如果正在生成中，把它实时画在屏幕左下角（打字机效果）
        if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
            String drawText = "§b[天枢] §f" + currentLlmReply.toString();
            
            // Y坐标设定在屏幕底部往上 40 像素（刚好在聊天输入框上方）
            int y = screenHeight - 40;
            
            // 画字（带阴影）
            guiGraphics.drawString(Minecraft.getInstance().font, drawText, 4, y, 0xFFFFFF, true);
        }
    }
}
