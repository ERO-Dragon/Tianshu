package com.rheinmetal.tianshu.neoforge.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.llm.performance.ClientLlmRuntimeBridge;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeWorldIdentityCapture;
import com.rheinmetal.tianshu.neoforge.ui.hud.PresenceHudRenderer;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.function.Supplier;

public final class NeoForgeClientEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final TianshuSettingsModule settingsModule;
    private final NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private final PresenceClientRuntime presenceRuntime;
    private final PresenceHudRenderer presenceHudRenderer;
    private final NeoForgeWorldIdentityCapture worldIdentityCapture;
    private final Supplier<KeyMapping> voiceKeySupplier;
    private final NeoForgeVoiceInputController voiceInputController;

    public NeoForgeClientEvents(
            ClientConfig config,
            TianshuCoreManager coreManager,
            TianshuSettingsModule settingsModule,
            NeoForgeClientLifecycleAdapter lifecycleAdapter,
            PresenceClientRuntime presenceRuntime,
            PresenceHudRenderer presenceHudRenderer,
            NeoForgeWorldIdentityCapture worldIdentityCapture,
            Supplier<KeyMapping> voiceKeySupplier
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(coreManager, "coreManager");
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
        this.lifecycleAdapter = Objects.requireNonNull(lifecycleAdapter, "lifecycleAdapter");
        this.presenceRuntime = Objects.requireNonNull(presenceRuntime, "presenceRuntime");
        this.presenceHudRenderer = Objects.requireNonNull(presenceHudRenderer, "presenceHudRenderer");
        this.worldIdentityCapture = Objects.requireNonNull(worldIdentityCapture, "worldIdentityCapture");
        this.voiceKeySupplier = Objects.requireNonNull(voiceKeySupplier, "voiceKeySupplier");
        this.voiceInputController = new NeoForgeVoiceInputController(
                config::getTriggerMode,
                () -> coreManager.findService(AsrInputService.class),
                presenceRuntime::recordVoiceKeyInput
        );
    }

    @SubscribeEvent
    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("NEOFORGE_WORLD_LOGIN");
        worldIdentityCapture.refresh();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        lifecycleAdapter.onWorldLogin();
    }

    @SubscribeEvent
    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        worldIdentityCapture.refresh();
    }

    @SubscribeEvent
    public void onWorldLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("NEOFORGE_WORLD_LOGOUT");
        resetVoiceInputState();
        worldIdentityCapture.clear();
        presenceHudRenderer.clear();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        lifecycleAdapter.onWorldLogout();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        presenceHudRenderer.update();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (!worldIdentityCapture.isAvailable()) {
            worldIdentityCapture.refresh();
        }
        lifecycleAdapter.onClientTick();

        KeyMapping voiceKey = voiceKeySupplier.get();
        voiceInputController.tick(voiceKey != null, voiceKey != null && voiceKey.isDown());
    }

    @SubscribeEvent
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

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        ClientLlmRuntimeBridge.markFrame();
        presenceHudRenderer.render(event.getGuiGraphics(), 0.0F);
    }

    @SubscribeEvent
    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event instanceof ClientChatReceivedEvent.Player && !event.isSystem()) {
            presenceRuntime.recordPlayerChatMessage(
                    event.getMessage().getString(),
                    event.getSender() == null ? "" : event.getSender().toString(),
                    playerChatSenderName(event)
            );
        }
    }

    @SubscribeEvent
    public void onKeyboardInput(InputEvent.Key event) {
        if (event.getAction() != InputConstants.RELEASE) {
            presenceRuntime.recordKeyboardInput();
        }
    }

    @SubscribeEvent
    public void onMouseButtonInput(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.PRESS) {
            presenceRuntime.recordMouseInput();
        }
    }

    @SubscribeEvent
    public void onMouseScrollInput(InputEvent.MouseScrollingEvent event) {
        presenceRuntime.recordMouseInput();
    }

    public void resetVoiceInputState() {
        voiceInputController.reset();
    }

    private static String playerChatSenderName(ClientChatReceivedEvent event) {
        if (event.getBoundChatType() == null || event.getBoundChatType().name() == null) {
            return "";
        }
        return event.getBoundChatType().name().getString();
    }
}
