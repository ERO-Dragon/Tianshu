package com.rheinmetal.tianshu.neoforge.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.llm.performance.ClientLlmRuntimeBridge;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.ui.hud.PresenceHudRenderer;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class NeoForgeClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ClientConfig config;
    private final TianshuCoreManager coreManager;
    private final TianshuSettingsModule settingsModule;
    private final NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private final PresenceClientRuntime presenceRuntime;
    private final PresenceHudRenderer presenceHudRenderer;
    private final Supplier<KeyMapping> voiceKeySupplier;

    private boolean wasAlwaysKeyTriggered;
    private boolean voiceKeyPressed;
    private TriggerMode lastTriggerMode;

    public NeoForgeClientEvents(
            ClientConfig config,
            TianshuCoreManager coreManager,
            TianshuSettingsModule settingsModule,
            NeoForgeClientLifecycleAdapter lifecycleAdapter,
            PresenceClientRuntime presenceRuntime,
            PresenceHudRenderer presenceHudRenderer,
            Supplier<KeyMapping> voiceKeySupplier
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
        this.lifecycleAdapter = Objects.requireNonNull(lifecycleAdapter, "lifecycleAdapter");
        this.presenceRuntime = Objects.requireNonNull(presenceRuntime, "presenceRuntime");
        this.presenceHudRenderer = Objects.requireNonNull(presenceHudRenderer, "presenceHudRenderer");
        this.voiceKeySupplier = Objects.requireNonNull(voiceKeySupplier, "voiceKeySupplier");
    }

    public void register(IEventBus eventBus) {
        Objects.requireNonNull(eventBus, "eventBus");
        eventBus.addListener(this::onClientTick);
        eventBus.addListener(this::onScreenInit);
        eventBus.addListener(this::onRenderGui);
        eventBus.addListener(this::onClientChatReceived);
        eventBus.addListener(this::onKeyboardInput);
        eventBus.addListener(this::onMouseButtonInput);
        eventBus.addListener(this::onMouseScrollInput);
        eventBus.addListener(this::onWorldLogin);
        eventBus.addListener(this::onWorldLogout);
    }

    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("检测到客户端登录世界，准备拉起引擎...");
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        lifecycleAdapter.onWorldLogin();
    }

    public void onWorldLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("检测到客户端退出世界，开始清理...");
        resetVoiceInputState();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        lifecycleAdapter.onWorldLogout();
    }

    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        lifecycleAdapter.onClientTick();

        if (!config.isAiEnabled()) {
            if (voiceKeyPressed) {
                voiceKeyPressed = false;
                cancelVoiceInput();
                lastTriggerMode = null;
            }
            return;
        }

        handleVoiceKey();
    }

    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        presenceRuntime.recordScreenChanged();
        if (!(screen instanceof PauseScreen)) {
            return;
        }

        int buttonWidth = 200;
        int buttonHeight = 20;
        int buttonX = (screen.width - buttonWidth) / 2;
        int maxY = 0;
        for (GuiEventListener widget : screen.children()) {
            if (widget instanceof Button button) {
                maxY = Math.max(maxY, button.getY() + button.getHeight());
            }
        }
        event.addListener(Button.builder(
                Component.translatable("tianshu.gui.settings.console"),
                button -> settingsModule.openScreen()
        ).pos(buttonX, maxY + 5).size(buttonWidth, buttonHeight).build());
    }

    public void onRenderGui(RenderGuiEvent.Post event) {
        ClientLlmRuntimeBridge.markFrame();
        presenceHudRenderer.render(event.getGuiGraphics(), 0.0F);
    }

    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event instanceof ClientChatReceivedEvent.Player && !event.isSystem()) {
            presenceRuntime.recordPlayerChatMessage(
                    event.getMessage().getString(),
                    event.getSender() == null ? "" : event.getSender().toString(),
                    playerChatSenderName(event)
            );
        }
    }

    public void onKeyboardInput(InputEvent.Key event) {
        if (event.getAction() != InputConstants.RELEASE) {
            presenceRuntime.recordKeyboardInput();
        }
    }

    public void onMouseButtonInput(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.PRESS) {
            presenceRuntime.recordMouseInput();
        }
    }

    public void onMouseScrollInput(InputEvent.MouseScrollingEvent event) {
        presenceRuntime.recordMouseInput();
    }

    public void resetVoiceInputState() {
        voiceKeyPressed = false;
        wasAlwaysKeyTriggered = false;
        lastTriggerMode = null;
    }

    private Optional<AsrInputService> asrInputService() {
        return coreManager.findService(AsrInputService.class);
    }

    private void handleVoiceKey() {
        Optional<AsrInputService> input = asrInputService();
        KeyMapping voiceKey = voiceKeySupplier.get();
        if (input.isEmpty() || voiceKey == null) {
            resetVoiceInputState();
            return;
        }
        AsrInputService inputService = input.get();
        if (!inputService.canAcceptVoiceInput()) {
            return;
        }

        TriggerMode currentMode = config.getTriggerMode();
        if (lastTriggerMode != null && lastTriggerMode != currentMode) {
            LOGGER.info("检测到模式切换: {} -> {}, 执行全局清理", lastTriggerMode, currentMode);
            cancelVoiceInput();
            voiceKeyPressed = false;
            wasAlwaysKeyTriggered = false;
        }

        switch (currentMode) {
            case ALWAYS -> handleAlwaysMode(voiceKey);
            case PUSH_TO_TALK -> handlePushToTalkMode(voiceKey);
        }
        lastTriggerMode = currentMode;
    }

    private void handleAlwaysMode(KeyMapping voiceKey) {
        if (!voiceKeyPressed) {
            voiceKeyPressed = true;
            beginVoiceInput();
            LOGGER.info("启动常开模式");
        }
        boolean keyDown = voiceKey.isDown();
        if (keyDown && !wasAlwaysKeyTriggered) {
            wasAlwaysKeyTriggered = true;
            commitVoiceInput();
        } else if (!keyDown) {
            wasAlwaysKeyTriggered = false;
        }
    }

    private void handlePushToTalkMode(KeyMapping voiceKey) {
        boolean keyDown = voiceKey.isDown();
        if (keyDown && !voiceKeyPressed) {
            voiceKeyPressed = true;
            beginVoiceInput();
        } else if (!keyDown && voiceKeyPressed) {
            voiceKeyPressed = false;
            endVoiceInput();
        }
    }

    private void beginVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::beginVoiceInput);
    }

    private void endVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::endVoiceInput);
    }

    private void commitVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::commitVoiceInput);
    }

    private void cancelVoiceInput() {
        recordPresenceVoiceKeyInput();
        asrInputService().ifPresent(AsrInputService::cancelVoiceInput);
    }

    private void recordPresenceVoiceKeyInput() {
        presenceRuntime.recordVoiceKeyInput();
    }

    private static String playerChatSenderName(ClientChatReceivedEvent event) {
        if (event.getBoundChatType() == null || event.getBoundChatType().name() == null) {
            return "";
        }
        return event.getBoundChatType().name().getString();
    }
}
