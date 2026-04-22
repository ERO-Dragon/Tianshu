package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.gui.TianshuGUI;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class TianshuClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping VOICE_KEY;

    private static boolean wasAlwaysKeyTriggered = false;
    private static boolean isVoiceKeyPressed = false;
    private static TriggerMode lastTriggerMode = null;
    private static boolean isOnnxRuntimeLoaded = false;
    private static final StringBuilder currentLlmReply = new StringBuilder();

    private static NeoForgeEnvironment env;
    private static NeoForgeConfig config;
    private static NeoForgeNativeLibBridge nativeLibBridge;
    private static AudioManager audioManager;
    private static TianshuCoreManager coreManager;

    public static void init() {
        LOGGER.info("天枢 AI 客户端事件开始注册...");
        env = new NeoForgeEnvironment();
        config = new NeoForgeConfig();
        nativeLibBridge = new NeoForgeNativeLibBridge();
        nativeLibBridge.ensureDirectories();
        nativeLibBridge.extractAndLoadAll();

        audioManager = new AudioManager();
        audioManager.ensureHardwareRunning();

        coreManager = new TianshuCoreManager(env, config, nativeLibBridge, audioManager);

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> {
            LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
            if (!isOnnxRuntimeLoaded) {
                try {
                    LOGGER.info("正在加载Onnx自己的 onnxruntime.dll为OnnxRuntime和SherpaOnnx提供支持");
                    // 让它把 onnxruntime.dll 解压到 Temp 目录，并加载到进程内存中！
                    ai.onnxruntime.OrtEnvironment.getEnvironment();
                } catch (Throwable t) {
                }
                isOnnxRuntimeLoaded = true;
            }
            coreManager.tryInitEngine();
            coreManager.initWorkers();
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
            LOGGER.info("检测到客户端退出世界，开始清理...");
            shutdownClient();
            coreManager.destroy();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("检测到 JVM 即将关闭，执行最终清理...");
            coreManager.destroy();
            audioManager.shutdown();
        }, "Tianshu-Shutdown-Hook"));
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        VOICE_KEY = new KeyMapping(
                "key.tianshu.activate",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.tianshu"
        );
        event.register(VOICE_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(PlayerTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;

        if (!config.isAiEnabled()) {
            if (isVoiceKeyPressed) {
                isVoiceKeyPressed = false;
                coreManager.getEventBus().publishEvent(new StopStreamRecordingEvent());
                coreManager.getEventBus().publishEvent(new StopListeningEvent());
                lastTriggerMode = null;
            }
            return;
        }

        handleVoiceKey();
    }

    private static void handleVoiceKey() {
        if (!coreManager.isEngineReady()) return;

        TriggerMode currentMode = config.getTriggerMode();

        if (lastTriggerMode != null && lastTriggerMode != currentMode) {
            LOGGER.info("检测到模式切换: {} -> {}, 执行全局清理", lastTriggerMode, currentMode);
            coreManager.getEventBus().publishEvent(new StopStreamRecordingEvent());
            coreManager.getEventBus().publishEvent(new StopListeningEvent());
            isVoiceKeyPressed = false;
            wasAlwaysKeyTriggered = false;
        }

        switch (currentMode) {
            case ALWAYS -> {
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    coreManager.getEventBus().publishEvent(new StartStreamRecordingEvent());
                    LOGGER.info("启动常开模式");
                }
                boolean isDown = VOICE_KEY.isDown();
                if (isDown && !wasAlwaysKeyTriggered) {
                    wasAlwaysKeyTriggered = true;
                    coreManager.getEventBus().publishEvent(new ForceAsrFlushEvent());
                } else if (!isDown) {
                    wasAlwaysKeyTriggered = false;
                }
            }
            case PUSH_TO_TALK -> {
                boolean isTriggered = VOICE_KEY.isDown();
                if (isTriggered && !isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    coreManager.getEventBus().publishEvent(new StartListeningEvent());
                } else if (!isTriggered && isVoiceKeyPressed) {
                    isVoiceKeyPressed = false;
                    coreManager.getEventBus().publishEvent(new StopListeningEvent());
                }
            }
            case WAKE_WORD -> {
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    coreManager.getEventBus().publishEvent(new StartStreamRecordingEvent());
                    LOGGER.info("启动热词模式");
                }
            }
        }

        lastTriggerMode = currentMode;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof PauseScreen) {
            int screenWidth = screen.width;
            int buttonWidth = 200;
            int buttonHeight = 20;
            int buttonX = (screenWidth - buttonWidth) / 2;

            int maxY = 0;
            for (GuiEventListener widget : screen.children()) {
                if (widget instanceof Button button) {
                    int buttonBottomY = button.getY() + button.getHeight();
                    if (buttonBottomY > maxY) {
                        maxY = buttonBottomY;
                    }
                }
            }
            int myButtonY = maxY + 5;

            event.addListener(Button.builder(
                    Component.literal("天枢 AI 控制台"),
                    (button) -> Minecraft.getInstance().setScreen(new TianshuGUI(coreManager, config, audioManager, nativeLibBridge))
            ).pos(buttonX, myButtonY).size(buttonWidth, buttonHeight).build());
        }
    }

    public static void registerOverlays(RegisterGuiLayersEvent event) {
        event.registerAbove(
                ResourceLocation.withDefaultNamespace("chat"),
                ResourceLocation.fromNamespaceAndPath("tianshu", "llm_reply"),
                TianshuClient::renderLlmReply
        );
    }

    public static void renderLlmReply(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (coreManager == null) return;
        TianshuEventBus eventBus = coreManager.getEventBus();
        if (eventBus == null) return;

        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        while (!eventBus.getUiQueue().isEmpty()) {
            TianshuEvent e = eventBus.getUiQueue().poll();
            if (e instanceof AsrFinalTextEvent asrEvent) {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("\u00a7a[ASR] \u00a7f" + asrEvent.getText()), false
                    );
                }
            } else if (e instanceof LlmChunkEvent chunk) {
                currentLlmReply.append(chunk.getText());
            } else if (e instanceof LlmEndEvent) {
                if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("\u00a7b[天枢] \u00a7f" + currentLlmReply.toString()), false
                    );
                    currentLlmReply.setLength(0);
                }
            }
        }

        if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
            String drawText = "\u00a7b[天枢] \u00a7f" + currentLlmReply.toString();
            int y = screenHeight - 40;
            guiGraphics.drawString(Minecraft.getInstance().font, drawText, 4, y, 0xFFFFFF, true);
        }
    }

    public static void shutdownClient() {
        LOGGER.info("关闭天枢客户端资源");
        coreManager.destroy();
        isVoiceKeyPressed = false;
        lastTriggerMode = null;
        currentLlmReply.setLength(0);
        LOGGER.info("天枢客户端资源清理完成");
    }
}
