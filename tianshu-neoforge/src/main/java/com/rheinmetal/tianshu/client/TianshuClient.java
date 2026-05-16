package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.gui.asr.AsrSettingsRegistrySource;
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
import com.rheinmetal.tianshu.event.TianshuEvent;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.event.UiAsrTextEvent;
import com.rheinmetal.tianshu.event.UiLlmEndEvent;
import com.rheinmetal.tianshu.event.UiLlmTextEvent;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.platform.NeoForgeAssistantWorldIdentityProvider;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import com.rheinmetal.tianshu.platform.provider.NeoForgeAudioEventProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeEnvironmentProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeInventoryProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgePlayerStateProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeRecipeProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeSocialDataProvider;
import com.rheinmetal.tianshu.platform.provider.NeoForgeTargetScanner;
import com.rheinmetal.tianshu.platform.provider.NeoForgeWorldDataProvider;
import com.rheinmetal.tianshu.provider.IAudioEventProvider;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.provider.IRecipeDataProvider;
import com.rheinmetal.tianshu.provider.ISocialDataProvider;
import com.rheinmetal.tianshu.provider.ITargetScannerProvider;
import com.rheinmetal.tianshu.provider.IWorldDataProvider;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
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
    private static final StringBuilder currentLlmReply = new StringBuilder();

    private static NeoForgeEnvironment env;
    private static ClientConfig config;
    private static NeoForgeNativeLibBridge nativeLibBridge;
    private static AudioManager audioManager;
    private static TianshuCoreManager coreManager;
    private static TianshuSettingsModule settingsModule;
    private static TianshuSettingsContributorRegistry externalSettingsContributors;
    private static CoreBackedTianshuIntegrationApi integrationApi;

    private static ITargetScannerProvider targetScanner;
    private static IInventoryDataProvider inventoryProvider;
    private static IEnvironmentAwarenessProvider environmentProvider;
    private static IPlayerStateProvider playerStateProvider;
    private static IRecipeDataProvider recipeProvider;
    private static IWorldDataProvider worldDataProvider;
    private static ISocialDataProvider socialDataProvider;
    private static IAudioEventProvider audioEventProvider;
    private static WorldStateProvider worldStateProvider;

    private static AsrInputService asrInputService() {
        return coreManager.requireService(AsrInputService.class);
    }

    private static TianshuSettingsRegistrySource createSettingsRegistrySource() {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        TianshuSettingsRegistrySource asrSource = new AsrSettingsRegistrySource(coreManager, config, audioManager);
        TianshuSettingsRegistrySource ttsSource = new TtsSettingsRegistrySource(coreManager, config);
        return CompositeSettingsRegistrySource.of(moduleSource, externalSource, asrSource, ttsSource);
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

        targetScanner = new NeoForgeTargetScanner();
        inventoryProvider = new NeoForgeInventoryProvider();
        environmentProvider = new NeoForgeEnvironmentProvider();
        playerStateProvider = new NeoForgePlayerStateProvider();
        recipeProvider = new NeoForgeRecipeProvider();
        worldDataProvider = new NeoForgeWorldDataProvider();
        socialDataProvider = new NeoForgeSocialDataProvider();
        audioEventProvider = new NeoForgeAudioEventProvider();

        worldStateProvider = new WorldStateProvider(
                playerStateProvider,
                inventoryProvider,
                environmentProvider,
                targetScanner,
                worldDataProvider,
                recipeProvider,
                socialDataProvider,
                audioEventProvider
        );

        coreManager = new TianshuCoreManager(env, config, nativeLibBridge, audioManager, context -> new ClientTianshuModuleAssembler(
                context.env(),
                context.config(),
                context.nativeLibBridge(),
                context.audioBridge(),
                context.eventBus(),
                context.protocolRuntime(),
                context.voiceInputGate(),
                context.interruptionSignal(),
                new NeoForgeAssistantWorldIdentityProvider(),
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
                    Component.literal("天枢 AI 控制台"),
                    button -> settingsModule.openScreen()
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
        TianshuEventBus eventBus = coreManager.eventBus();
        if (eventBus == null) return;

        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        while (!eventBus.getUiQueue().isEmpty()) {
            TianshuEvent e = eventBus.getUiQueue().poll();
            if (e instanceof UiAsrTextEvent asrEvent) {
                String asrText = asrEvent.getText();
                LOGGER.info("[IR-ASR] ASR 识别文本: {}", asrText);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§a[ASR] §f" + asrText), false
                    );
                }
            } else if (e instanceof UiLlmTextEvent chunk) {
                currentLlmReply.append(chunk.getText());
            } else if (e instanceof UiLlmEndEvent) {
                if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§b[天枢] §f" + currentLlmReply), false
                    );
                    currentLlmReply.setLength(0);
                }
            }
        }

        if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
            String drawText = "§b[天枢] §f" + currentLlmReply;
            int y = screenHeight - 40;
            guiGraphics.drawString(Minecraft.getInstance().font, drawText, 4, y, 0xFFFFFF, true);
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
        currentLlmReply.setLength(0);
        LOGGER.info("天枢客户端资源清理完成");
    }
}
