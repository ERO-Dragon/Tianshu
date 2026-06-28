package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.gui.auxilium.AXChatHudRenderer;
import com.rheinmetal.tianshu.client.gui.auxilium.AXChatHudState;
import com.rheinmetal.tianshu.client.gui.auxilium.AXClientConfig;
import com.rheinmetal.tianshu.client.gui.auxilium.AXSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.asr.AsrSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.llm.ClientLlmRuntimeBridge;
import com.rheinmetal.tianshu.client.gui.llm.LlmSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.presence.hud.ClientConfigPresenceHudSettings;
import com.rheinmetal.tianshu.client.gui.presence.hud.PresenceHudRenderer;
import com.rheinmetal.tianshu.client.gui.presence.settings.PresenceSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.module.TianshuSettingsModule;
import com.rheinmetal.tianshu.client.gui.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.ExternalSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.ModuleSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsContributorRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.tts.TtsSettingsRegistrySource;
import com.rheinmetal.tianshu.client.integration.TianshuIntegrationRegisterEvent;
import com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager;
import com.rheinmetal.tianshu.client.lifecycle.ClientTianshuModuleAssembler;
import com.rheinmetal.tianshu.client.ir.NamedObjectReloadListener;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.client.presence.PresenceClientHooks;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.integration.CoreBackedTianshuIntegrationApi;
import com.rheinmetal.tianshu.integration.TianshuIntegrationAccess;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.platform.NeoForgeAXWorldIdentityProvider;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgePresencePlatform;
import com.rheinmetal.tianshu.platform.NeoForgePresenceTextProvider;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

public class TianshuClient {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping VOICE_KEY;

    private static boolean wasAlwaysKeyTriggered = false;
    private static boolean isVoiceKeyPressed = false;
    private static TriggerMode lastTriggerMode = null;
    private static boolean isOnnxRuntimeLoaded = false;
    private static boolean worldSessionStarted = false;

    private static NeoForgeEnvironment env;
    private static ClientConfig config;
    private static AudioManager audioManager;
    private static AXClientConfig axConfig;
    private static AXChatHudState axChatHudState;
    private static AXChatHudRenderer axChatHudRenderer;
    private static TianshuCoreManager coreManager;
    private static TianshuSettingsModule settingsModule;
    private static TianshuSettingsContributorRegistry externalSettingsContributors;
    private static CoreBackedTianshuIntegrationApi integrationApi;

    private static PresenceClientRuntime presenceRuntime;
    private static PresenceHudRenderer presenceHudRenderer;

    private static Optional<AsrInputService> asrInputService() {
        return coreManager == null ? Optional.empty() : coreManager.findService(AsrInputService.class);
    }

    private static TianshuSettingsRegistrySource createSettingsRegistrySource() {
        TianshuSettingsRegistrySource moduleSource = new ModuleSettingsRegistrySource(coreManager::managedModules);
        TianshuSettingsRegistrySource externalSource = new ExternalSettingsRegistrySource(externalSettingsContributors);
        TianshuSettingsRegistrySource asrSource = new AsrSettingsRegistrySource(coreManager, config, audioManager);
        TianshuSettingsRegistrySource ttsSource = new TtsSettingsRegistrySource(coreManager, config);
        TianshuSettingsRegistrySource llmSource = new LlmSettingsRegistrySource(coreManager, config);
        TianshuSettingsRegistrySource axSource = new AXSettingsRegistrySource(coreManager, axConfig);
        TianshuSettingsRegistrySource presenceSource = new PresenceSettingsRegistrySource(config);
        return CompositeSettingsRegistrySource.of(moduleSource, externalSource, asrSource, llmSource, ttsSource, axSource, presenceSource);
    }

    private static void beginVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::beginVoiceInput);
    }

    private static void endVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::endVoiceInput);
    }

    private static void commitVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::commitVoiceInput);
    }

    private static void cancelVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::cancelVoiceInput);
    }

    private static void recordPresenceVoiceKeyInput() {
        if (presenceRuntime != null) {
            presenceRuntime.recordVoiceKeyInput();
        }
    }

    public static void init() {
        LOGGER.info("天枢 AI 客户端事件开始注册...");
        env = new NeoForgeEnvironment();
        config = new ClientConfig();
        axConfig = new AXClientConfig(config.getRootPath().resolve("ax"));
        axChatHudState = new AXChatHudState();
        axChatHudRenderer = new AXChatHudRenderer(axChatHudState, axConfig);
        presenceRuntime = new PresenceClientRuntime(new NeoForgePresencePlatform(), new NeoForgePresenceTextProvider());
        presenceHudRenderer = new PresenceHudRenderer(presenceRuntime::currentHudDisplay, new ClientConfigPresenceHudSettings(config));
        PresenceClientHooks.bind(presenceRuntime);

        audioManager = new AudioManager();
        String selectedMicName = config.getSelectedMicName();
        if (selectedMicName != null && !selectedMicName.isBlank()) {
            audioManager.selectMic(selectedMicName);
        }

        coreManager = new TianshuCoreManager(env, config, audioManager, context -> new ClientTianshuModuleAssembler(
                context.env(),
                context.config(),
                context.audioBridge(),
                context.protocolRuntime(),
                context.voiceInputGate(),
                context.interruptionSignal(),
                new NeoForgeAXWorldIdentityProvider(),
                axConfig,
                axChatHudState,
                List.of(presenceRuntime.moduleInstaller(context.protocolRuntime()))
        ));
        externalSettingsContributors = new TianshuSettingsContributorRegistry();
        integrationApi = new CoreBackedTianshuIntegrationApi(coreManager);
        TianshuIntegrationAccess.publish(integrationApi);
        NeoForge.EVENT_BUS.post(new TianshuIntegrationRegisterEvent(integrationApi, externalSettingsContributors));
        settingsModule = new TianshuSettingsModule(coreManager, createSettingsRegistrySource());

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onRenderGui);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientChatReceived);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onKeyboardInput);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseButtonInput);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseScrollInput);

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> {
            LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
            ClientNamedObjectIndexManager.ensureIndex("client login");
            ensureOnnxRuntimeLoaded();
            coreManager.initWorkers();
            ClientLlmRuntimeBridge.bind(coreManager, config);
            worldSessionStarted = coreManager.isInitialized();
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
            LOGGER.info("检测到客户端退出世界，开始清理...");
            stopWorldSession();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("检测到 JVM 即将关闭，执行最终清理...");
            shutdownClient();
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
        event.registerReloadListener(new NamedObjectReloadListener());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || config == null) return;
        if (presenceRuntime != null) {
            presenceRuntime.tick();
        }

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
        Optional<AsrInputService> input = asrInputService();
        if (input.isEmpty()) {
            isVoiceKeyPressed = false;
            wasAlwaysKeyTriggered = false;
            lastTriggerMode = null;
            return;
        }
        AsrInputService inputService = input.get();
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
        }

        lastTriggerMode = currentMode;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (presenceRuntime != null) {
            presenceRuntime.recordScreenChanged();
        }
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

    public static void onRenderGui(RenderGuiEvent.Post event) {
        ClientLlmRuntimeBridge.markFrame();
        if (presenceHudRenderer != null) {
            presenceHudRenderer.render(event.getGuiGraphics(), 0.0F);
        }
        if (axChatHudRenderer != null) {
            axChatHudRenderer.render(event.getGuiGraphics(), 0.0F);
        }
    }

    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        if (presenceRuntime != null && event instanceof ClientChatReceivedEvent.Player && !event.isSystem()) {
            presenceRuntime.recordPlayerChatMessage(
                    event.getMessage().getString(),
                    event.getSender() == null ? "" : event.getSender().toString(),
                    playerChatSenderName(event)
            );
        }
    }

    private static String playerChatSenderName(ClientChatReceivedEvent event) {
        if (event == null || event.getBoundChatType() == null || event.getBoundChatType().name() == null) {
            return "";
        }
        return event.getBoundChatType().name().getString();
    }

    public static void onKeyboardInput(InputEvent.Key event) {
        if (presenceRuntime != null && event.getAction() != InputConstants.RELEASE) {
            presenceRuntime.recordKeyboardInput();
        }
    }

    public static void onMouseButtonInput(InputEvent.MouseButton.Post event) {
        if (presenceRuntime != null && event.getAction() == InputConstants.PRESS) {
            presenceRuntime.recordMouseInput();
        }
    }

    public static void onMouseScrollInput(InputEvent.MouseScrollingEvent event) {
        if (presenceRuntime != null) {
            presenceRuntime.recordMouseInput();
        }
    }

    private static void stopWorldSession() {
        if (!worldSessionStarted) {
            isVoiceKeyPressed = false;
            wasAlwaysKeyTriggered = false;
            lastTriggerMode = null;
            LOGGER.info("Ignoring world logout before Tianshu session start");
            return;
        }
        LOGGER.info("Stopping Tianshu world session");
        if (coreManager != null) {
            coreManager.stopRuntimeSession();
        }
        if (audioManager != null) {
            audioManager.releaseCaptureHardware();
        }
        isVoiceKeyPressed = false;
        wasAlwaysKeyTriggered = false;
        lastTriggerMode = null;
        worldSessionStarted = false;
        LOGGER.info("Tianshu world session stopped");
    }

    public static void shutdownClient() {
        LOGGER.info("关闭天枢客户端资源");
        PresenceClientRuntime previousPresenceRuntime = presenceRuntime;
        presenceRuntime = null;
        presenceHudRenderer = null;
        PresenceClientHooks.clear(previousPresenceRuntime);
        if (integrationApi != null) {
            TianshuIntegrationAccess.clear(integrationApi);
            integrationApi = null;
        }
        if (coreManager != null) {
            coreManager.destroy();
            coreManager = null;
        }
        if (audioManager != null) {
            audioManager.shutdown();
            audioManager = null;
        }
        isVoiceKeyPressed = false;
        wasAlwaysKeyTriggered = false;
        lastTriggerMode = null;
        worldSessionStarted = false;
        LOGGER.info("天枢客户端资源清理完成");
    }
}
