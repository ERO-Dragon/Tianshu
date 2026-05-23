package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.gui.asr.AsrSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.llm.LlmSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.module.TianshuSettingsModule;
import com.rheinmetal.tianshu.client.gui.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.tts.TtsSettingsRegistrySource;
import com.rheinmetal.tianshu.client.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.client.ir.ClientItemCommandManager;
import com.rheinmetal.tianshu.client.ir.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.ir.ItemCommandReloadListener;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.platform.NeoForgeAXWorldIdentityProvider;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import com.rheinmetal.tianshu.platform.provider.NeoForgeEnvironmentProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeInventoryProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgePlayerStateProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeSocialDataProvider;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.provider.ISocialDataProvider;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class TianshuClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping VOICE_KEY;

    private static boolean wasAlwaysKeyTriggered = false;
    private static boolean isVoiceKeyPressed = false;
    private static TriggerMode lastTriggerMode = null;
    private static boolean isOnnxRuntimeLoaded = false;

    private static NeoForgeEnvironment env;
    private static ClientConfig config;
    private static NeoForgeNativeLibBridge nativeLibBridge;
    private static AudioManager audioManager;
    private static TianshuCoreManager coreManager;
    private static TianshuSettingsModule settingsModule;
    private static TianshuSettingsContributorRegistry externalSettingsContributors;
    private static CoreBackedTianshuIntegrationApi integrationApi;

    private static IInventoryDataProvider inventoryProvider;
    private static IEnvironmentAwarenessProvider environmentProvider;
    private static IPlayerStateProvider playerStateProvider;
    private static ISocialDataProvider socialDataProvider;
    private static WorldStateProvider worldStateProvider;

    private static AsrInputService asrInputService() {
        return coreManager.requireService(AsrInputService.class);
    }

    private static TianshuSettingsRegistrySource createSettingsRegistrySource() {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        TianshuSettingsRegistrySource asrSource = new AsrSettingsRegistrySource(coreManager, config, audioManager);
        TianshuSettingsRegistrySource ttsSource = new TtsSettingsRegistrySource(coreManager, config);
        TianshuSettingsRegistrySource llmSource = new LlmSettingsRegistrySource(coreManager, config);
        return CompositeSettingsRegistrySource.of(moduleSource, externalSource, asrSource, ttsSource, llmSource);
    }

    private static void beginVoiceInput() {
        asrInputService().beginVoiceInput();
    }

    private static void endVoiceInput() {
        asrInputService().endVoiceInput();
    }

    private static void commitVoiceInput() {
        asrInputService().commitVoiceInput();
    }

    private static void cancelVoiceInput() {
        if (coreManager != null) {
            coreManager.findService(AsrInputService.class).ifPresent(AsrInputService::cancelVoiceInput);
        }
    }

    public static void init() {
        LOGGER.info("天枢 AI 客户端事件开始注册...");
        env = new NeoForgeEnvironment();
        config = new ClientConfig();
        nativeLibBridge = new NeoForgeNativeLibBridge();
        nativeLibBridge.ensureDirectories();
        nativeLibBridge.extractAndLoadAll();

        audioManager = new AudioManager();
        String selectedMicName = config.getSelectedMicName();
        if (selectedMicName != null && !selectedMicName.isBlank()) {
            audioManager.selectMic(selectedMicName);
        }
        if (config.isAsrEnabled()) {
            audioManager.ensureHardwareRunning();
        }

        inventoryProvider = new NeoForgeInventoryProvider();
        environmentProvider = new NeoForgeEnvironmentProvider();
        playerStateProvider = new NeoForgePlayerStateProvider();
        socialDataProvider = new NeoForgeSocialDataProvider();

        worldStateProvider = new WorldStateProvider(
                playerStateProvider,
                inventoryProvider,
                environmentProvider,
                socialDataProvider
        );

        coreManager = new TianshuCoreManager(env, config, nativeLibBridge, audioManager, context -> new ClientTianshuModuleAssembler(
                context.env(),
                context.config(),
                context.nativeLibBridge(),
                context.audioBridge(),
                context.protocolRuntime(),
                context.voiceInputGate(),
                context.interruptionSignal(),
                new NeoForgeAXWorldIdentityProvider(),
                worldStateProvider
        ));
        externalSettingsContributors = new TianshuSettingsContributorRegistry();
        integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
        TianshuIntegrationAccess.publish(integrationApi);
        NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(integrationApi, externalSettingsContributors));
        settingsModule = new TianshuSettingsModule(coreManager, createSettingsRegistrySource());

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> {
            LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
            ClientItemCommandManager.ensureIndex("client login");
            ensureOnnxRuntimeLoaded();
            coreManager.initWorkers();
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
            LOGGER.info("检测到客户端退出世界，开始清理...");
            shutdownClient();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("检测到 JVM 即将关闭，执行最终清理...");
            if (integrationApi != null) TianshuIntegrationAccess.clear(integrationApi);
            if (coreManager != null) coreManager.destroy();
            if (audioManager != null) audioManager.shutdown();
        }, "Tianshu-Shutdown-Hook"));
    }

    private static void ensureOnnxRuntimeLoaded() {
        if (isOnnxRuntimeLoaded) return;
        try {
            LOGGER.info("正在加载Onnx自己的 onnxruntime.dll为OnnxRuntime和SherpaOnnx提供支持");
            ai.onnxruntime.OrtEnvironment.getEnvironment();
        } catch (Throwable ignored) {
        }
        isOnnxRuntimeLoaded = true;
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

    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ItemCommandReloadListener());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || config == null) return;

        if (!config.isAiEnabled()) {
            if (isVoiceKeyPressed) {
                isVoiceKeyPressed = false;
                cancelVoiceInput();
                lastTriggerMode = null;
            }
            return;
        }

        handleVoiceKey();
    }

    private static void handleVoiceKey() {
        AsrInputService inputService = asrInputService();
        if (!inputService.canAcceptVoiceInput()) return;

        TriggerMode currentMode = config.getTriggerMode();

        if (lastTriggerMode != null && lastTriggerMode != currentMode) {
            LOGGER.info("检测到模式切换: {} -> {}, 执行全局清理", lastTriggerMode, currentMode);
            cancelVoiceInput();
            isVoiceKeyPressed = false;
            wasAlwaysKeyTriggered = false;
        }

        switch (currentMode) {
            case ALWAYS -> {
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    beginVoiceInput();
                    LOGGER.info("启动常开模式");
                }
                boolean isDown = VOICE_KEY.isDown();
                if (isDown && !wasAlwaysKeyTriggered) {
                    wasAlwaysKeyTriggered = true;
                    commitVoiceInput();
                } else if (!isDown) {
                    wasAlwaysKeyTriggered = false;
                }
            }
            case PUSH_TO_TALK -> {
                boolean isTriggered = VOICE_KEY.isDown();
                if (isTriggered && !isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    beginVoiceInput();
                } else if (!isTriggered && isVoiceKeyPressed) {
                    isVoiceKeyPressed = false;
                    endVoiceInput();
                }
            }
            case WAKE_WORD -> {
                if (!isVoiceKeyPressed) {
                    isVoiceKeyPressed = true;
                    beginVoiceInput();
                    LOGGER.info("启动热词模式");
                }
                boolean isDown = VOICE_KEY.isDown();
                if (isDown && !wasAlwaysKeyTriggered) {
                    wasAlwaysKeyTriggered = true;
                    commitVoiceInput();
                } else if (!isDown) {
                    wasAlwaysKeyTriggered = false;
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
                    Component.translatable("tianshu.gui.settings.console"),
                    button -> settingsModule.openScreen()
            ).pos(buttonX, myButtonY).size(buttonWidth, buttonHeight).build());
        }
    }

    public static void shutdownClient() {
        LOGGER.info("关闭天枢客户端资源");
        if (integrationApi != null) {
            TianshuIntegrationAccess.clear(integrationApi);
        }
        if (coreManager != null) {
            coreManager.destroy();
        }
        if (audioManager != null) {
            audioManager.shutdown();
        }
        isVoiceKeyPressed = false;
        wasAlwaysKeyTriggered = false;
        lastTriggerMode = null;
        LOGGER.info("天枢客户端资源清理完成");
    }
}
