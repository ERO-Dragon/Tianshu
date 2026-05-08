package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.chatassistant.ChatAssistantClientBridge;
import com.rheinmetal.tianshu.client.chatassistant.ChatAssistantOverlayRenderer;
import com.rheinmetal.tianshu.client.junk.JunkCleanerClientController;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantInterruptPayload;
import com.rheinmetal.tianshu.client.craftinggraph.CraftingGraphController;
import com.rheinmetal.tianshu.client.craftinggraph.CraftingGraphInteractionScreen;
import com.rheinmetal.tianshu.client.geminicard.GeminiCardTooltipAdapter;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphStorage;
import com.rheinmetal.tianshu.client.ir.ClientItemCommandManager;
import com.rheinmetal.tianshu.client.ir.ItemCommandReloadListener;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.AcousticRadar.AcousticRadarEngine;
import com.rheinmetal.tianshu.function.AcousticRadar.AlertSpeaker;
import com.rheinmetal.tianshu.function.AcousticRadar.DefaultAlertTextProvider;
import com.rheinmetal.tianshu.function.AcousticRadar.RadarOutput;
import com.rheinmetal.tianshu.function.AcousticRadar.RadarIndicator;
import com.rheinmetal.tianshu.function.MR.MrConstants;
import com.rheinmetal.tianshu.function.MR.MrEngine;
import com.rheinmetal.tianshu.function.MR.MrTuningProvider;
import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.function.ir.IrCommandParser;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.gui.TianshuGUI;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import com.rheinmetal.tianshu.snapshot.MrManualFocusTargetData;
import com.rheinmetal.tianshu.platform.provider.*;
import com.rheinmetal.tianshu.provider.*;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class TianshuClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping VOICE_KEY;
    public static KeyMapping CRAFTING_GRAPH_INTERACTION_KEY;
    public static KeyMapping MR_TOGGLE_KEY;

    private static final float MR_ALT_SHORT_PRESS_SECONDS = 0.16f;
    private static final float MR_ALT_FOCUS_SECONDS = 2.0f;
    private static final float MR_ALT_FOCUS_DECAY_SECONDS = 2.0f;
    private static long mrAltPressStartedNanos = 0L;
    private static long mrAltLastNanos = 0L;
    private static boolean mrAltWasDown = false;
    private static boolean mrAltFocusTriggered = false;
    private static float mrAltFocusChargeSeconds = 0.0f;
    private static MrManualFocusTargetData mrAltFocusTarget = null;

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
    private static ChatAssistantClientBridge chatAssistantClientBridge;
    private static JunkCleanerClientController junkCleanerClientController;

    private static ITargetScannerProvider targetScanner;
    private static IInventoryDataProvider inventoryProvider;
    private static IEnvironmentAwarenessProvider environmentProvider;
    private static IPlayerStateProvider playerStateProvider;
    private static IRecipeDataProvider recipeProvider;
    private static IWorldDataProvider worldDataProvider;
    private static IRenderContextProvider renderContextProvider;
    private static ISocialDataProvider socialDataProvider;
    private static IAudioEventProvider audioEventProvider;
    private static WorldStateProvider worldStateProvider;

    private static AcousticRadarEngine acousticRadarEngine;
    private static int acousticRadarTickCounter = 0;
    private static final int ACOUSTIC_RADAR_INTERVAL = 2;

    private static MrEngine mrEngine;
    private static MrRenderer mrRenderer;
    private static int mrTickCounter = 0;
    private static boolean mrUserEnabled = false;

    private static CraftingGraphController craftingGraphController;
    private static CraftingGraphStorage craftingGraphStorage;
    private static long lastCraftingGraphDebugMillis = 0L;

    // 二级雷达：已播报过的指示器（防止重复刷屏）
    private static final Set<String> announcedIndicators = new java.util.HashSet<>();
    // 二级雷达：指示器过期清理计时
    private static int indicatorClearCounter = 0;

    public static ITargetScannerProvider getTargetScanner() { return targetScanner; }
    public static IInventoryDataProvider getInventoryProvider() { return inventoryProvider; }
    public static IEnvironmentAwarenessProvider getEnvironmentProvider() { return environmentProvider; }
    public static IPlayerStateProvider getPlayerStateProvider() { return playerStateProvider; }
    public static IRecipeDataProvider getRecipeProvider() { return recipeProvider; }
    public static IWorldDataProvider getWorldDataProvider() { return worldDataProvider; }
    public static IRenderContextProvider getRenderContextProvider() { return renderContextProvider; }
    public static ISocialDataProvider getSocialDataProvider() { return socialDataProvider; }
    public static IAudioEventProvider getAudioEventProvider() { return audioEventProvider; }
    public static WorldStateProvider getWorldStateProvider() { return worldStateProvider; }

    private static IrCommandParser createClientIrCommandParser() {
        return new IrCommandParser() {
            @Override
            public IRParseResult parse(String text, boolean fastIr) {
                return ClientItemCommandManager.parsePlayerCommand(text, fastIr);
            }

            @Override
            public String formatPreview(IRParseResult result) {
                return ClientItemCommandManager.formatPreview(result);
            }
        };
    }

    public static void init() {
        LOGGER.info("天枢 AI 客户端事件开始注册...");
        env = new NeoForgeEnvironment();
        config = new ClientConfig();
        nativeLibBridge = new NeoForgeNativeLibBridge();
        nativeLibBridge.ensureDirectories();
        nativeLibBridge.extractAndLoadAll();

        audioManager = new AudioManager();
        audioManager.ensureHardwareRunning();

        coreManager = new TianshuCoreManager(env, config, nativeLibBridge, audioManager);
        coreManager.setIrCommandParser(createClientIrCommandParser());
        chatAssistantClientBridge = new ChatAssistantClientBridge(coreManager.getProtocolRuntime());
        chatAssistantClientBridge.register();
        junkCleanerClientController = new JunkCleanerClientController();

        targetScanner = new NeoForgeTargetScanner();
        inventoryProvider = new NeoForgeInventoryProvider();
        environmentProvider = new NeoForgeEnvironmentProvider();
        playerStateProvider = new NeoForgePlayerStateProvider();
        recipeProvider = new NeoForgeRecipeProvider();
        worldDataProvider = new NeoForgeWorldDataProvider();
        renderContextProvider = new NeoForgeRenderContextProvider();
        socialDataProvider = new NeoForgeSocialDataProvider();
        audioEventProvider = new NeoForgeAudioEventProvider();

        worldStateProvider = new WorldStateProvider(
                playerStateProvider,
                inventoryProvider,
                environmentProvider,
                targetScanner,
                worldDataProvider,
                recipeProvider,
                renderContextProvider,
                socialDataProvider,
                audioEventProvider
        );

        ensureCraftingGraphController("init");

        ClientConfig.syncToFeatureManager();

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseClickedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseReleasedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseDraggedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseScrolledPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onGameMouseButtonPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onGameMouseScrolled);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onGameKeyInput);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onKeyPressedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onCharTypedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onChatReceived);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onLivingDeath);

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> {
            LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
            ClientItemCommandManager.ensureIndex("client login");
            if (!isOnnxRuntimeLoaded) {
                try {
                    LOGGER.info("正在加载Onnx自己的 onnxruntime.dll为OnnxRuntime和SherpaOnnx提供支持");
                    // 让它把 onnxruntime.dll 解压到 Temp 目录，并加载到进程内存中！
                    ai.onnxruntime.OrtEnvironment.getEnvironment();
                } catch (Throwable t) {
                }
                isOnnxRuntimeLoaded = true;
            }
            coreManager.initWorkers();
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> {
            LOGGER.info("检测到客户端退出世界，开始清理...");
            if (chatAssistantClientBridge != null) {
                chatAssistantClientBridge.forceInterrupt(ChatAssistantInterruptPayload.Reason.WORLD_LOGOUT, "world_logout");
            }
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
        CRAFTING_GRAPH_INTERACTION_KEY = new KeyMapping(
                "key.tianshu.crafting_graph_interaction",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_TAB,
                "key.categories.tianshu"
        );
        MR_TOGGLE_KEY = new KeyMapping(
                "key.tianshu.mr_toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.tianshu"
        );
        event.register(VOICE_KEY);
        event.register(CRAFTING_GRAPH_INTERACTION_KEY);
        event.register(MR_TOGGLE_KEY);
    }

    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ItemCommandReloadListener());
    }

    private static void onChatReceived(ClientChatReceivedEvent event) {
        if (chatAssistantClientBridge == null || event == null) {
            return;
        }
        Component message = event.getMessage();
        if (message == null) {
            return;
        }
        String messageText = message.getString();
        if (messageText == null || messageText.isBlank()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String playerName = minecraft.player == null ? "unknown" : minecraft.player.getName().getString();
        boolean mentionsSelf = !"unknown".equals(playerName) && messageText.contains(playerName);
        chatAssistantClientBridge.publishIncomingChat(extractChatSender(messageText), messageText, playerName, mentionsSelf);
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (chatAssistantClientBridge == null || event == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || event.getEntity() == null) {
            return;
        }
        if (!event.getEntity().getUUID().equals(minecraft.player.getUUID())) {
            return;
        }
        chatAssistantClientBridge.forceInterrupt(ChatAssistantInterruptPayload.Reason.PLAYER_DEATH, "player_death");
    }

    private static String extractChatSender(String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return "System";
        }
        int ltIdx = messageText.indexOf('<');
        int gtIdx = messageText.indexOf('>');
        if (ltIdx == 0 && gtIdx > ltIdx) {
            return messageText.substring(1, gtIdx).trim();
        }
        return "System";
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        GeminiCardTooltipAdapter.tickSessionExpiry();
        if (minecraft.player == null) return;

        if (!config.isAiEnabled()) {
            if (isVoiceKeyPressed) {
                isVoiceKeyPressed = false;
                coreManager.getEventBus().publishEvent(new StopStreamRecordingEvent());
                coreManager.getEventBus().publishEvent(new StopListeningEvent());
                lastTriggerMode = null;
            }
            tickCraftingGraph();
            return;
        }

        handleVoiceKey();
        handleMrAltKey(minecraft);
        if (chatAssistantClientBridge != null) {
            chatAssistantClientBridge.tick();
        }
        tickAcousticRadar(minecraft);
        tickMrSystem(minecraft);
        tickCraftingGraph();
    }

    private static void handleMrAltKey(Minecraft minecraft) {
        if (MR_TOGGLE_KEY == null || !FeatureManager.isTacticalMrEnabled()) {
            resetMrAltInputState(true);
            if (!FeatureManager.isTacticalMrEnabled()) mrUserEnabled = false;
            return;
        }

        boolean focusInputAllowed = isMrFocusInputAllowed(minecraft);

        long now = System.nanoTime();
        if (mrAltLastNanos == 0L) mrAltLastNanos = now;
        float deltaSeconds = Math.max(0.0f, Math.min(0.25f, (now - mrAltLastNanos) / 1_000_000_000.0f));
        mrAltLastNanos = now;

        boolean altDown = MR_TOGGLE_KEY.isDown();
        if (altDown && !mrAltWasDown) {
            mrAltPressStartedNanos = now;
            mrAltFocusTriggered = false;
        }

        if (altDown) {
            float heldSeconds = (now - mrAltPressStartedNanos) / 1_000_000_000.0f;
            if (focusInputAllowed && heldSeconds >= MR_ALT_SHORT_PRESS_SECONDS) {
                tickMrAltFocusCharge(minecraft, heldSeconds);
            } else if (!focusInputAllowed) {
                clearMrAltFocusCharge();
            }
        } else {
            if (mrAltWasDown) {
                float heldSeconds = (now - mrAltPressStartedNanos) / 1_000_000_000.0f;
                if (heldSeconds < MR_ALT_SHORT_PRESS_SECONDS && mrAltFocusChargeSeconds <= 0.0f && !mrAltFocusTriggered) {
                    toggleMrUserEnabled();
                }
            }
            decayMrAltFocusCharge(deltaSeconds);
        }

        mrAltWasDown = altDown;
    }

    private static void tickMrAltFocusCharge(Minecraft minecraft, float heldSeconds) {
        if (mrAltFocusTriggered) return;
        if (mrAltFocusTarget == null || mrAltFocusChargeSeconds <= 0.0f) {
            mrAltFocusTarget = environmentProvider.getManualFocusTarget(MrConstants.MR_RANGE);
        }
        if (mrAltFocusTarget == null) return;

        ensureMrEngineForManualFocus(mrUserEnabled);
        if (mrEngine == null) return;

        mrAltFocusChargeSeconds = Math.min(MR_ALT_FOCUS_SECONDS, Math.max(mrAltFocusChargeSeconds, heldSeconds));
        mrEngine.previewManualFocusProgress(mrAltFocusTarget, mrUserEnabled, mrAltFocusChargeSeconds / MR_ALT_FOCUS_SECONDS);
        environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());

        if (mrAltFocusChargeSeconds >= MR_ALT_FOCUS_SECONDS) {
            mrAltFocusTriggered = true;
            mrAltFocusChargeSeconds = 0.0f;
            mrEngine.startManualFocus(mrAltFocusTarget, mrUserEnabled);
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            LOGGER.info("[MR] Alt 长按聚焦目标 {}", mrAltFocusTarget.getUuid());
            mrAltFocusTarget = null;
        }
    }

    private static void decayMrAltFocusCharge(float deltaSeconds) {
        if (mrAltFocusChargeSeconds <= 0.0f) {
            if (mrEngine != null) mrEngine.clearManualFocusPreview();
            mrAltFocusTarget = null;
            return;
        }
        float decaySpeed = MR_ALT_FOCUS_DECAY_SECONDS > 0.0f ? MR_ALT_FOCUS_SECONDS / MR_ALT_FOCUS_DECAY_SECONDS : MR_ALT_FOCUS_SECONDS;
        mrAltFocusChargeSeconds = Math.max(0.0f, mrAltFocusChargeSeconds - deltaSeconds * decaySpeed);
        if (mrAltFocusTarget != null) {
            ensureMrEngineForManualFocus(mrUserEnabled);
            if (mrEngine != null) {
                mrEngine.previewManualFocusProgress(mrAltFocusTarget, mrUserEnabled, mrAltFocusChargeSeconds / MR_ALT_FOCUS_SECONDS);
                environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            }
        }
        if (mrAltFocusChargeSeconds <= 0.0f) {
            if (mrEngine != null) mrEngine.clearManualFocusPreview();
            mrAltFocusTarget = null;
        }
    }

    private static void toggleMrUserEnabled() {
        if (!FeatureManager.isTacticalMrEnabled()) {
            mrUserEnabled = false;
            LOGGER.info("[MR] 总控关闭，忽略用户开关请求");
            return;
        }
        if (!mrUserEnabled && mrEngine != null && mrEngine.hasManualFocusTarget()) {
            mrEngine.cancelManualFocus();
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            LOGGER.info("[MR] 手动聚焦面板开始关闭");
            return;
        }
        boolean nextEnabled = !mrUserEnabled;
        if (!nextEnabled && mrEngine != null && mrEngine.hasManualFocusTarget()) {
            mrEngine.clearManualFocusTarget();
        }
        mrUserEnabled = nextEnabled;
        LOGGER.info("[MR] 用户{}全息战术系统", mrUserEnabled ? "开启" : "关闭");
    }

    private static void resetMrAltInputState(boolean clearCharge) {
        mrAltPressStartedNanos = 0L;
        mrAltLastNanos = 0L;
        mrAltWasDown = false;
        mrAltFocusTriggered = false;
        if (clearCharge) {
            mrAltFocusChargeSeconds = 0.0f;
            mrAltFocusTarget = null;
            if (mrEngine != null) mrEngine.clearManualFocusPreview();
        }
    }

    private static void clearMrAltFocusCharge() {
        mrAltFocusChargeSeconds = 0.0f;
        mrAltFocusTarget = null;
        if (mrEngine != null) mrEngine.clearManualFocusPreview();
    }

    private static boolean isMrFocusInputAllowed(Minecraft minecraft) {
        return minecraft != null && minecraft.screen == null;
    }

    private static void ensureMrEngineForManualFocus(boolean includeBackgroundCards) {
        if (mrEngine != null) {
            if (mrEngine.isClosing()) {
                mrEngine.stop();
                mrEngine = null;
                mrRenderer = null;
            } else {
                mrEngine.setScanningCardsEnabled(includeBackgroundCards);
                return;
            }
        }
        mrEngine = new MrEngine(environmentProvider, renderContextProvider, playerStateProvider, createMrTuningProvider());
        mrRenderer = new MrRenderer(mrEngine);
        mrEngine.setScanningCardsEnabled(includeBackgroundCards);
        mrTickCounter = 0;
    }

    private static void handleVoiceKey() {
        if (!coreManager.canAcceptVoiceInput()) return;

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
                boolean isDown = VOICE_KEY.isDown();
                if (isDown && !wasAlwaysKeyTriggered) {
                    wasAlwaysKeyTriggered = true;
                    coreManager.getEventBus().publishEvent(new ForceAsrFlushEvent());
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
        if (ensureCraftingGraphController("screenInit")) {
            craftingGraphController.onExternalScreenOpened(screen);
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
        event.registerAbove(
                ResourceLocation.fromNamespaceAndPath("tianshu", "llm_reply"),
                ResourceLocation.fromNamespaceAndPath("tianshu", "mr_cards"),
                TianshuClient::renderMrCards
        );
        event.registerAbove(
                ResourceLocation.fromNamespaceAndPath("tianshu", "mr_cards"),
                ResourceLocation.fromNamespaceAndPath("tianshu", "crafting_graph"),
                TianshuClient::renderCraftingGraph
        );
        event.registerAbove(
                ResourceLocation.fromNamespaceAndPath("tianshu", "crafting_graph"),
                ResourceLocation.fromNamespaceAndPath("tianshu", "chat_assistant"),
                TianshuClient::renderChatAssistant
        );
        event.registerAbove(
                ResourceLocation.fromNamespaceAndPath("tianshu", "chat_assistant"),
                ResourceLocation.fromNamespaceAndPath("tianshu", "junk_overlay"),
                TianshuClient::renderJunkOverlay
        );
    }

    public static void renderJunkOverlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (junkCleanerClientController == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || !(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return;
        for (var slot : screen.getMenu().slots) {
            if (!slot.hasItem() || !junkCleanerClientController.isJunk(slot.getItem())) continue;
            int x = screen.getGuiLeft() + slot.x + 11;
            int y = screen.getGuiTop() + slot.y + 1;
            guiGraphics.fill(x, y, x + 5, y + 5, 0x99D34A4A);
            guiGraphics.fill(x + 1, y + 5, x + 4, y + 7, 0x99D34A4A);
        }
    }

    public static void renderChatAssistant(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (chatAssistantClientBridge == null) return;
        ChatAssistantOverlayRenderer.render(guiGraphics, chatAssistantClientBridge.state());
    }

    public static void renderLlmReply(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (coreManager == null) return;
        TianshuEventBus eventBus = coreManager.getEventBus();
        if (eventBus == null) return;

        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        while (!eventBus.getUiQueue().isEmpty()) {
            TianshuEvent e = eventBus.getUiQueue().poll();
            if (e instanceof UiAsrTextEvent asrEvent) {
                String asrText = asrEvent.getText();
                LOGGER.info("[IR-ASR] ASR 识别文本: {}", asrText);
                if (junkCleanerClientController != null && junkCleanerClientController.handleAsrText(asrText)) {
                    continue;
                }
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("\u00a7a[ASR] \u00a7f" + asrText), false
                    );
                }
            } else if (e instanceof UiLlmTextEvent chunk) {
                currentLlmReply.append(chunk.getText());
            } else if (e instanceof UiLlmEndEvent) {
                if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("\u00a7b[天枢] \u00a7f" + currentLlmReply.toString()), false
                    );
                    currentLlmReply.setLength(0);
                }
            } else if (e instanceof TtsPlaybackEndEvent ttsEnd) {
                if ("acoustic_radar".equals(ttsEnd.getSource()) && acousticRadarEngine != null) {
                    acousticRadarEngine.onTtsPlaybackFinished();
                }
            }
        }

        if (!currentLlmReply.isEmpty() && Minecraft.getInstance().player != null) {
            String drawText = "\u00a7b[天枢] \u00a7f" + currentLlmReply.toString();
            int y = screenHeight - 40;
            guiGraphics.drawString(Minecraft.getInstance().font, drawText, 4, y, 0xFFFFFF, true);
        }
    }

    private static void tickAcousticRadar(Minecraft minecraft) {
        if (!FeatureManager.isAudioRadarEnabled()) {
            if (acousticRadarEngine != null) {
                acousticRadarEngine.shutdown();
                acousticRadarEngine = null;
                LOGGER.info("[战术雷达] 功能已关闭，释放引擎实例");
            }
            return;
        }

        if (minecraft.player == null || minecraft.level == null) return;

        if (acousticRadarEngine == null) {
            acousticRadarEngine = new AcousticRadarEngine(
                    environmentProvider,
                    playerStateProvider,
                    audioEventProvider,
                    new AlertSpeaker() {
                        @Override
                        public void speakAlert(String text) {
                            coreManager.speakAlert(text);
                        }

                        @Override
                        public void speakAlertWithInterrupt(String text) {
                            coreManager.speakAlertWithInterrupt(text);
                        }
                    },
                    new DefaultAlertTextProvider(),
                    // 【新增】：将底层 Provider 的方法包装成回调传进去
                    requiredRadius -> {
                        double mrRadius = (mrEngine != null && mrEngine.isRunning()) ? mrEngine.getRequiredRadius() : 0.0;
                        environmentProvider.setActiveScanRadius(Math.max(requiredRadius, mrRadius));
                    }
            );
            acousticRadarTickCounter = 0;
            acousticRadarEngine.start();
            LOGGER.info("[战术雷达] 功能已开启");
        }

        acousticRadarTickCounter++;
        if (acousticRadarTickCounter < ACOUSTIC_RADAR_INTERVAL) return;
        acousticRadarTickCounter = 0;

        try {
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());

            var player = minecraft.player;
            com.rheinmetal.tianshu.snapshot.PositionData playerPos =
                    new com.rheinmetal.tianshu.snapshot.PositionData(
                            player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot(),
                            player.level().dimension().location().toString(),
                            player.getUUID().toString()
                    );

            RadarOutput output = acousticRadarEngine.tickSync(playerPos);
            if (output == null || output.getIndicators().isEmpty()) {
                announcedIndicators.clear();
                return;
            }

            // 每 60 tick 清理一次已播报记录，允许同类型怪物重新触发
            indicatorClearCounter++;
            if (indicatorClearCounter >= 60) {
                announcedIndicators.clear();
                indicatorClearCounter = 0;
            }

            for (RadarIndicator indicator : output.getIndicators()) {
                // 用 entityType 作为唯一键，同类型怪物只播报一次
                String indicatorKey = indicator.getEntityType();
                if (announcedIndicators.contains(indicatorKey)) continue;
                announcedIndicators.add(indicatorKey);

                String dir = computeDirectionLabel(indicator.getRelativeAngle());
                String msg = "\u00a7e[雷达] \u00a7f" + dir + " " + indicator.getDisplayName()
                        + " " + String.format("%.0f", indicator.getDistance()) + "格";
                minecraft.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(msg), false);
            }
        } catch (Exception e) {
            LOGGER.warn("战术雷达tick异常: {}", e.getMessage());
        }
    }

    private static String computeDirectionLabel(double relativeAngle) {
        double abs = Math.abs(relativeAngle);
        if (abs < 22.5) return "前方";
        else if (abs < 67.5) return relativeAngle > 0 ? "右前方" : "左前方";
        else if (abs < 112.5) return relativeAngle > 0 ? "右方" : "左方";
        else if (abs <157.5) return relativeAngle > 0 ? "右后方" : "左后方";
        else return "后方";
    }

    public static void shutdownClient() {
        LOGGER.info("关闭天枢客户端资源");
        coreManager.destroy();
        if (craftingGraphController != null) {
            craftingGraphController.shutdown();
            craftingGraphController = null;
            debugCraftingGraph("shutdown controller=null");
        }
        craftingGraphStorage = null;
        if (acousticRadarEngine != null) {
            acousticRadarEngine.shutdown();
            acousticRadarEngine = null;
        }
        if (mrEngine != null) {
            mrEngine.stop();
            mrEngine = null;
            mrRenderer = null;
        }
        if (chatAssistantClientBridge != null) {
            chatAssistantClientBridge.close();
            chatAssistantClientBridge = null;
        }
        isVoiceKeyPressed = false;
        lastTriggerMode = null;
        currentLlmReply.setLength(0);
        LOGGER.info("天枢客户端资源清理完成");
    }

    private static double computeRequiredEnvironmentScanRadius() {
        double radarRadius = acousticRadarEngine != null ? acousticRadarEngine.getRadarRange() : 0.0;
        double mrRadius = (mrEngine != null && mrEngine.isRunning() && FeatureManager.isTacticalMrEnabled())
                ? mrEngine.getRequiredRadius()
                : 0.0;
        return Math.max(radarRadius, mrRadius);
    }

    private static void tickMrSystem(Minecraft minecraft) {
        boolean focusInteractionAllowed = isMrFocusInputAllowed(minecraft);
        boolean mrActiveRequested = FeatureManager.isTacticalMrEnabled()
                && (mrUserEnabled || (mrEngine != null && ((mrEngine.hasManualFocusTarget() || mrEngine.hasManualFocusPreview()) && !mrEngine.isClosing())));
        boolean mrClosingRequested = !mrActiveRequested;
        if (!FeatureManager.isTacticalMrEnabled()) {
            mrUserEnabled = false;
        }
        if (mrEngine != null && mrEngine.isClosing() && !mrClosingRequested) {
            mrEngine.stop();
            mrEngine = null;
            mrRenderer = null;
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            LOGGER.info("[MR] 检测到重新开启请求，已中止关闭动画并重建引擎");
        }
        if (mrClosingRequested && mrEngine != null && !mrEngine.isClosing()) {
            mrEngine.beginClosing();
            LOGGER.info("[MR] 全息战术系统开始关闭动画");
        }

        if (minecraft.player == null || minecraft.level == null) return;

        if (mrEngine == null) {
            if (mrClosingRequested) return;
            mrEngine = new MrEngine(environmentProvider, renderContextProvider, playerStateProvider, createMrTuningProvider());
            mrRenderer = new MrRenderer(mrEngine);
            mrEngine.setFocusInteractionEnabled(focusInteractionAllowed);
            mrEngine.start();
            mrEngine.setScanningCardsEnabled(mrUserEnabled);
            mrTickCounter = 0;
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            LOGGER.info("[MR] 全息战术系统已启动");
        }

        if (mrClosingRequested || mrEngine.isClosing()) {
            try {
                var player = minecraft.player;
                com.rheinmetal.tianshu.snapshot.PositionData playerPos =
                        new com.rheinmetal.tianshu.snapshot.PositionData(
                                player.getX(), player.getY(), player.getZ(),
                                player.getYRot(), player.getXRot(),
                                player.level().dimension().location().toString(),
                                player.getUUID().toString()
                        );
                float mrDeltaTime = MrConstants.TICK_DURATION * MrConstants.TICK_INTERVAL;
                mrEngine.tick(playerPos, mrDeltaTime);
            } catch (Exception e) {
                LOGGER.warn("[MR] 关闭动画 tick 异常: {}", e.getMessage());
            }

            if (mrEngine.isCloseAnimationFinished()) {
                mrEngine.stop();
                mrEngine = null;
                mrRenderer = null;
                environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
                LOGGER.info("[MR] 全息战术系统关闭完成");
            }
            return;
        }

        mrTickCounter++;
        if (mrTickCounter < MrConstants.TICK_INTERVAL) return;
        mrTickCounter = 0;

        mrEngine.setFocusInteractionEnabled(focusInteractionAllowed);

        try {
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());

            var player = minecraft.player;
            com.rheinmetal.tianshu.snapshot.PositionData playerPos =
                    new com.rheinmetal.tianshu.snapshot.PositionData(
                            player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot(),
                            player.level().dimension().location().toString(),
                            player.getUUID().toString()
                    );
            float mrDeltaTime = MrConstants.TICK_DURATION * MrConstants.TICK_INTERVAL;
            mrEngine.tick(playerPos, mrDeltaTime);
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
        } catch (Exception e) {
            LOGGER.warn("[MR] tick异常: {}", e.getMessage());
        }
    }

    public static void renderMrCards(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (mrRenderer != null) {
            mrRenderer.render(guiGraphics, deltaTracker);
        }
    }

    private static MrTuningProvider createMrTuningProvider() {
        return new MrTuningProvider() {
            @Override
            public float getMinCardScale() {
                return ClientConfig.TACTICAL_MR_CARD_MIN_SCALE.get().floatValue();
            }

            @Override
            public float getMaxCardScale() {
                return ClientConfig.TACTICAL_MR_CARD_MAX_SCALE.get().floatValue();
            }

            @Override
            public float getSegmentLength() {
                return ClientConfig.TACTICAL_MR_SEGMENT_LENGTH.get().floatValue();
            }

            @Override
            public float getCardDamping() {
                return ClientConfig.TACTICAL_MR_CARD_DAMPING.get().floatValue();
            }

            @Override
            public float getCardMinDamping() {
                return ClientConfig.TACTICAL_MR_CARD_MIN_DAMPING.get().floatValue();
            }

            @Override
            public float getCardMaxDamping() {
                return ClientConfig.TACTICAL_MR_CARD_MAX_DAMPING.get().floatValue();
            }

            @Override
            public float getDayAlphaFactor() {
                return ClientConfig.TACTICAL_MR_DAY_ALPHA.get().floatValue();
            }

            @Override
            public float getNightAlphaFactor() {
                return ClientConfig.TACTICAL_MR_NIGHT_ALPHA.get().floatValue();
            }
        };
    }

    private static Path craftingGraphStorageRoot() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("TianshuAIAssistant")
                .resolve("module")
                .resolve("recipe")
                .resolve("cache")
                .resolve("crafting_graph");
    }

    private static boolean ensureCraftingGraphController(String reason) {
        if (craftingGraphController != null) return true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            debugCraftingGraphThrottled("ensure skipped reason=" + reason + " minecraft=null");
            return false;
        }
        if (recipeProvider == null || inventoryProvider == null) {
            debugCraftingGraphThrottled("ensure skipped reason=" + reason + " recipeProvider=" + (recipeProvider != null) + " inventoryProvider=" + (inventoryProvider != null));
            return false;
        }
        if (craftingGraphStorage == null) {
            craftingGraphStorage = new CraftingGraphStorage(craftingGraphStorageRoot());
        }
        craftingGraphController = new CraftingGraphController(recipeProvider, inventoryProvider, craftingGraphStorage);
        craftingGraphController.setInteractionKey(
                TianshuClient::isCraftingGraphInteractionKeyDown,
                TianshuClient::isCraftingGraphInteractionKey
        );
        craftingGraphController.setAlphaMultiplier(ClientConfig.CRAFTING_GRAPH_ALPHA.get().floatValue());
        debugCraftingGraph("ensure controller=true reason=" + reason + " storageRoot=" + craftingGraphStorageRoot());
        return true;
    }

    private static void debugCraftingGraph(String message) {
        try {
            Path logPath = Paths.get("logs", "crafting_graph_debug.txt");
            String line = "[" + System.currentTimeMillis() + "] " + message + "\n";
            Files.write(logPath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static void debugCraftingGraphThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastCraftingGraphDebugMillis < 1000L) return;
        lastCraftingGraphDebugMillis = now;
        debugCraftingGraph(message);
    }

    private static boolean isCraftingGraphInteractionKeyDown() {
        if (!isCraftingGraphInputAllowedScreen()) return false;
        if (CRAFTING_GRAPH_INTERACTION_KEY == null) return false;
        InputConstants.Key key = CRAFTING_GRAPH_INTERACTION_KEY.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return CRAFTING_GRAPH_INTERACTION_KEY.isDown();
        return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean isCraftingGraphInteractionKey(int keyCode) {
        return CRAFTING_GRAPH_INTERACTION_KEY != null && CRAFTING_GRAPH_INTERACTION_KEY.matches(keyCode, 0);
    }

    private static boolean isCraftingGraphEnabled() {
        ClientConfig.syncToFeatureManager();
        boolean enabled = FeatureManager.isRecipePanelEnabled();
        if (!enabled) {
            debugCraftingGraphThrottled("disabled ai=" + ClientConfig.AI_ENABLED.get() + " recipePanel=" + ClientConfig.RECIPE_PANEL_ENABLED.get());
        }
        return enabled;
    }

    private static boolean isCraftingGraphAllowedScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return false;
        Screen screen = minecraft.screen;
        return screen == null || screen instanceof AbstractContainerScreen<?>;
    }

    private static boolean isCraftingGraphInputAllowedScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return false;
        Screen screen = minecraft.screen;
        if (screen instanceof ChatScreen || screen instanceof PauseScreen) return false;
        return screen == null || screen instanceof CraftingGraphInteractionScreen || screen instanceof AbstractContainerScreen<?>;
    }

    private static void tickCraftingGraph() {
        if (!isCraftingGraphEnabled() || !isCraftingGraphAllowedScreen()) return;
        if (ensureCraftingGraphController("tick")) {
            craftingGraphController.tick();
        }
    }

    public static void renderCraftingGraph(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        renderCraftingGraph(guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    private static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof CraftingGraphInteractionScreen) return;
        renderCraftingGraph(event.getGuiGraphics(), event.getPartialTick());
    }

    private static void renderCraftingGraph(GuiGraphics guiGraphics, float partialTick) {
        if (!isCraftingGraphEnabled() || !isCraftingGraphAllowedScreen()) return;
        if (ensureCraftingGraphController("render")) {
            craftingGraphController.updateRenderFrameMouseDrag();
            craftingGraphController.getRenderer().render(guiGraphics, partialTick);
        }
    }

    private static void onMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("mouseClicked")
                && craftingGraphController.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseReleasedPre(ScreenEvent.MouseButtonReleased.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("mouseReleased")
                && craftingGraphController.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseDraggedPre(ScreenEvent.MouseDragged.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("mouseDragged")
                && craftingGraphController.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseScrolledPre(ScreenEvent.MouseScrolled.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("mouseScrolled")
                && craftingGraphController.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    private static void onKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("keyPressed")
                && craftingGraphController.keyPressed(event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    private static void onCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        if (isCraftingGraphEnabled() && isCraftingGraphInputAllowedScreen() && ensureCraftingGraphController("charTyped")
                && craftingGraphController.charTyped(event.getCodePoint())) {
            event.setCanceled(true);
        }
    }

    private static void onGameMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!isCraftingGraphEnabled() || !isCraftingGraphInputAllowedScreen() || !ensureCraftingGraphController("gameMouseButton")) return;
        Minecraft minecraft = Minecraft.getInstance();
        double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        boolean handled = event.getAction() == 1
                ? craftingGraphController.mouseClicked(mouseX, mouseY, event.getButton())
                : event.getAction() == 0 && craftingGraphController.mouseReleased(mouseX, mouseY, event.getButton());
        if (handled) event.setCanceled(true);
    }

    private static void onGameMouseScrolled(InputEvent.MouseScrollingEvent event) {
        if (!isCraftingGraphEnabled() || !isCraftingGraphInputAllowedScreen() || !ensureCraftingGraphController("gameMouseScrolled")) return;
        Minecraft minecraft = Minecraft.getInstance();
        double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        if (craftingGraphController.mouseScrolled(mouseX, mouseY, event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    private static void onGameKeyInput(InputEvent.Key event) {
        if (!isCraftingGraphEnabled() || !isCraftingGraphInputAllowedScreen() || !ensureCraftingGraphController("gameKey")) return;
        if (event.getAction() == 1 && event.getKey() == GLFW.GLFW_KEY_ESCAPE && craftingGraphController.isGameEditLocked()) {
            craftingGraphController.exitGameEdit();
        }
    }
}
