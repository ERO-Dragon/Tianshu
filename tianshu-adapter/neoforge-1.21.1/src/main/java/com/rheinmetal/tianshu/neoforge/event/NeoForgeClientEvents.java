package com.rheinmetal.tianshu.neoforge.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.neoforge.ui.TextDialogueScreen;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
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
import java.util.Objects;
import java.util.function.Supplier;

public final class NeoForgeClientEvents {
    private final LogSink logSink;
    private final TianshuCoreManager coreManager;
    private final TianshuSettingsModule settingsModule;
    private final NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private final PresenceClientRuntime presenceRuntime;
    private final PresenceHudRenderer presenceHudRenderer;
    private final NeoForgeWorldIdentityCapture worldIdentityCapture;
    private final Supplier<KeyMapping> voiceKeySupplier;
    private final Supplier<KeyMapping> textInputKeySupplier;
    private final NeoForgeKeyBindings keyBindings;
    private final NeoForgeVoiceInputController voiceInputController;

    public NeoForgeClientEvents(
            TianshuCoreManager coreManager,
            TianshuSettingsModule settingsModule,
            NeoForgeClientLifecycleAdapter lifecycleAdapter,
            PresenceClientRuntime presenceRuntime,
            PresenceHudRenderer presenceHudRenderer,
            NeoForgeWorldIdentityCapture worldIdentityCapture,
            Supplier<KeyMapping> voiceKeySupplier,
            Supplier<KeyMapping> textInputKeySupplier,
            NeoForgeKeyBindings keyBindings,
            LogSink logSink
    ) {
        this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
        this.lifecycleAdapter = Objects.requireNonNull(lifecycleAdapter, "lifecycleAdapter");
        this.presenceRuntime = Objects.requireNonNull(presenceRuntime, "presenceRuntime");
        this.presenceHudRenderer = Objects.requireNonNull(presenceHudRenderer, "presenceHudRenderer");
        this.worldIdentityCapture = Objects.requireNonNull(worldIdentityCapture, "worldIdentityCapture");
        this.voiceKeySupplier = Objects.requireNonNull(voiceKeySupplier, "voiceKeySupplier");
        this.textInputKeySupplier = Objects.requireNonNull(textInputKeySupplier, "textInputKeySupplier");
        this.keyBindings = Objects.requireNonNull(keyBindings, "keyBindings");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.voiceInputController = new NeoForgeVoiceInputController(presenceRuntime::recordVoiceKeyInput);
    }

    @SubscribeEvent
    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        logSink.info("neoforge.world.login");
        worldIdentityCapture.refresh();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        NeoForgeGameEventHooks.resetWorldSession();
        lifecycleAdapter.onWorldLogin();
    }

    @SubscribeEvent
    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        worldIdentityCapture.refresh();
    }

    @SubscribeEvent
    public void onWorldLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        logSink.info("neoforge.world.logout");
        resetVoiceInputState();
        worldIdentityCapture.clear();
        presenceHudRenderer.clear();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        // The world is still addressable here, so pending event aggregates can still reach history.
        NeoForgeGameEventHooks.flush();
        lifecycleAdapter.onWorldLogout();
        NeoForgeGameEventHooks.resetWorldSession();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (!worldIdentityCapture.isAvailable()) {
            worldIdentityCapture.refresh();
        }
        lifecycleAdapter.onClientTick();
        NeoForgeGameEventHooks.tick(minecraft);

        KeyMapping voiceKey = voiceKeySupplier.get();
        voiceInputController.tick(voiceKey != null, voiceKey != null && voiceKey.isDown());
        openTextDialogueIfListening();
        keyBindings.publishPressed();
    }

    /**
     * Opens the typed-dialogue bar, but only when something will answer it.
     *
     * <p>The question goes to the input bus rather than to a module: the module that answers a typed line
     * subscribes only while it is enabled, so asking the bus is the same question — and the platform still
     * knows no module by name.</p>
     */
    private void openTextDialogueIfListening() {
        KeyMapping textKey = textInputKeySupplier.get();
        Minecraft client = Minecraft.getInstance();
        if (textKey == null || client == null || client.screen != null) {
            return;
        }
        while (textKey.consumeClick()) {
            if (InputSignals.hasSubscribers(InputSignals.TEXT_SUBMITTED)) {
                client.setScreen(new TextDialogueScreen());
            }
        }
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
        presenceHudRenderer.render(event.getGuiGraphics());
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
