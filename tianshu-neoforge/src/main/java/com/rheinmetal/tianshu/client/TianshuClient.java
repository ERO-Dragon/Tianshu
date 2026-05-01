package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.client.craftinggraph.CraftingGraphController;
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
import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.event.*;
import com.rheinmetal.tianshu.ir.IRParseResult;
import com.rheinmetal.tianshu.gui.TianshuGUI;
import com.rheinmetal.tianshu.platform.NeoForgeEnvironment;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.nio.file.Path;
import java.util.Set;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class TianshuClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static KeyMapping VOICE_KEY;
    public static KeyMapping CRAFTING_GRAPH_INTERACTION_KEY;
    public static KeyMapping MR_TOGGLE_KEY;

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

        craftingGraphStorage = new CraftingGraphStorage(craftingGraphStorageRoot());
        craftingGraphController = new CraftingGraphController(recipeProvider, inventoryProvider, craftingGraphStorage);
        craftingGraphController.setInteractionKey(
                TianshuClient::isCraftingGraphInteractionKeyDown,
                TianshuClient::isCraftingGraphInteractionKey
        );
        craftingGraphController.setAlphaMultiplier(ClientConfig.CRAFTING_GRAPH_ALPHA.get().floatValue());

        ClientConfig.syncToFeatureManager();

        NeoForge.EVENT_BUS.addListener(TianshuClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onScreenInit);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseClickedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseReleasedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseDraggedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onMouseScrolledPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onKeyPressedPre);
        NeoForge.EVENT_BUS.addListener(TianshuClient::onCharTypedPre);

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
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
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
        handleMrToggleKey();
        tickAcousticRadar(minecraft);
        tickMrSystem(minecraft);
        tickCraftingGraph();
    }

    private static void handleMrToggleKey() {
        if (MR_TOGGLE_KEY == null) return;
        while (MR_TOGGLE_KEY.consumeClick()) {
            if (!FeatureManager.isTacticalMrEnabled()) {
                mrUserEnabled = false;
                LOGGER.info("[MR] 总控关闭，忽略用户开关请求");
                continue;
            }
            mrUserEnabled = !mrUserEnabled;
            LOGGER.info("[MR] 用户{}全息战术系统", mrUserEnabled ? "开启" : "关闭");
        }
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
    }

    public static void renderLlmReply(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (coreManager == null) return;
        TianshuEventBus eventBus = coreManager.getEventBus();
        if (eventBus == null) return;

        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        while (!eventBus.getUiQueue().isEmpty()) {
            TianshuEvent e = eventBus.getUiQueue().poll();
            if (e instanceof AsrFinalTextEvent asrEvent) {
                String asrText = asrEvent.getText();
                LOGGER.info("[IR-ASR] ASR 识别文本: {}", asrText);
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("\u00a7a[ASR] \u00a7f" + asrText), false
                    );

                    // 解析 ASR 识别文本
                    IRParseResult parseResult = ClientItemCommandManager.parsePlayerCommand(asrText, true);
                    String preview = ClientItemCommandManager.formatPreview(parseResult);
                    LOGGER.info("[IR-ASR] IR 解析结果: ready={}, units={}, preview={}", parseResult.isReady(), parseResult.hasUnits(), preview);
                    if (parseResult.hasUnits()) {
                        for (int i = 0; i < parseResult.getUnits().size(); i++) {
                            var unit = parseResult.getUnits().get(i);
                            LOGGER.info("[IR-ASR]   [{}] intent={}, target={}, negated={}", i, unit.intent, unit.targetRealItemId, unit.isNegated);
                        }
                    }
                    if (!preview.isEmpty()) {
                        Minecraft.getInstance().player.displayClientMessage(
                                Component.literal("\u00a76[IR] \u00a7f" + preview), false
                        );
                    }
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
        isVoiceKeyPressed = false;
        lastTriggerMode = null;
        currentLlmReply.setLength(0);
        LOGGER.info("天枢客户端资源清理完成");
    }

    private static double computeRequiredEnvironmentScanRadius() {
        double radarRadius = acousticRadarEngine != null ? acousticRadarEngine.getRadarRange() : 0.0;
        double mrRadius = (mrEngine != null && mrEngine.isRunning() && FeatureManager.isTacticalMrEnabled() && mrUserEnabled)
                ? mrEngine.getRequiredRadius()
                : 0.0;
        return Math.max(radarRadius, mrRadius);
    }

    private static void tickMrSystem(Minecraft minecraft) {
        if (!FeatureManager.isTacticalMrEnabled() || !mrUserEnabled) {
            if (!FeatureManager.isTacticalMrEnabled()) {
                mrUserEnabled = false;
            }
            if (mrEngine != null) {
                mrEngine.stop();
                mrEngine = null;
                mrRenderer = null;
                environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
                LOGGER.info("[MR] 全息战术系统已关闭，释放引擎实例");
            }
            return;
        }

        if (minecraft.player == null || minecraft.level == null) return;

        if (mrEngine == null) {
            mrEngine = new MrEngine(environmentProvider, renderContextProvider);
            mrRenderer = new MrRenderer(mrEngine);
            mrEngine.start();
            mrTickCounter = 0;
            environmentProvider.setActiveScanRadius(computeRequiredEnvironmentScanRadius());
            LOGGER.info("[MR] 全息战术系统已启动");
        }

        mrTickCounter++;
        if (mrTickCounter < MrConstants.TICK_INTERVAL) return;
        mrTickCounter = 0;

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

    private static Path craftingGraphStorageRoot() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("TianshuAIAssistant")
                .resolve("cache")
                .resolve("crafting_graph");
    }

    private static boolean isCraftingGraphInteractionKeyDown() {
        return CRAFTING_GRAPH_INTERACTION_KEY != null && CRAFTING_GRAPH_INTERACTION_KEY.isDown();
    }

    private static boolean isCraftingGraphInteractionKey(int keyCode) {
        return CRAFTING_GRAPH_INTERACTION_KEY != null && CRAFTING_GRAPH_INTERACTION_KEY.matches(keyCode, 0);
    }

    private static boolean isCraftingGraphEnabled() {
        ClientConfig.syncToFeatureManager();
        return FeatureManager.isRecipePanelEnabled();
    }

    private static void tickCraftingGraph() {
        if (!isCraftingGraphEnabled()) return;
        if (craftingGraphController != null) {
            craftingGraphController.tick();
        }
    }

    public static void renderCraftingGraph(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!isCraftingGraphEnabled()) return;
        if (craftingGraphController != null) {
            craftingGraphController.getRenderer().render(guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false));
        }
    }

    private static void onMouseClickedPre(ScreenEvent.MouseButtonPressed.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseReleasedPre(ScreenEvent.MouseButtonReleased.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.mouseReleased(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseDraggedPre(ScreenEvent.MouseDragged.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.mouseDragged(event.getMouseX(), event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY())) {
            event.setCanceled(true);
        }
    }

    private static void onMouseScrolledPre(ScreenEvent.MouseScrolled.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    private static void onKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.keyPressed(event.getKeyCode())) {
            event.setCanceled(true);
        }
    }

    private static void onCharTypedPre(ScreenEvent.CharacterTyped.Pre event) {
        if (isCraftingGraphEnabled() && craftingGraphController != null
                && craftingGraphController.charTyped(event.getCodePoint())) {
            event.setCanceled(true);
        }
    }
}
