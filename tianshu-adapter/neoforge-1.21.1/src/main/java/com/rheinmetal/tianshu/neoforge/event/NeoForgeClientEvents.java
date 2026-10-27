package com.rheinmetal.tianshu.neoforge.event;

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
        this.voiceInputController = new NeoForgeVoiceInputController();
    }

    @SubscribeEvent
    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        logSink.info("neoforge.world.login");
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
        logSink.info("neoforge.world.logout");
        resetVoiceInputState();
        worldIdentityCapture.clear();
        presenceHudRenderer.clear();
        NeoForgePresenceHooks.resetWorldSession(presenceRuntime);
        lifecycleAdapter.onWorldLogout();
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
        // The event layer owns two time-based doors (the deferred window and the immediate queue); this is the clock
        // that opens them. It carries no facts, so it stays even though the sampling port is gone.
        com.rheinmetal.tianshu.api.assembly.TickSignals.publish(
                com.rheinmetal.tianshu.api.assembly.TickSignals.TICK, System.currentTimeMillis());

        boolean moduleInputAvailable = moduleInputAvailable();
        KeyMapping voiceKey = voiceKeySupplier.get();
        voiceInputController.tick(
                voiceKey != null,
                voiceKey != null && voiceKey.isDown(),
                moduleInputAvailable
        );
        // The click queues are drained in both cases: a press swallowed during startup must not fire later.
        openTextDialogueIfListening(moduleInputAvailable);
        keyBindings.publishPressed(moduleInputAvailable);
    }

    /**
     * Whether module key input may be dispatched right now.
     *
     * <p>Two gates, one reading: the core must be running the session, and presence must not be reporting "loading".
     * Presence is the aggregated product state, so this is the same answer the HUD shows — the platform never
     * inspects an individual module's capabilities, and a module that is loading necessarily published its loading
     * activity for the HUD anyway.</p>
     */
    private boolean moduleInputAvailable() {
        return coreManager.isInitialized()
                && coreManager.runtimeReadyForRequests()
                && !presenceRuntime.isLoading();
    }

    /**
     * Opens the typed-dialogue bar, but only when something will answer it.
     *
     * <p>The question goes to the input bus rather than to a module: the module that answers a typed line
     * subscribes only while it is enabled, so asking the bus is the same question — and the platform still
     * knows no module by name.</p>
     *
     * <p>{@code openAllowed} is the session gate. The click queue is drained either way, so a press swallowed during
     * startup does not open the bar later, after the player already gave up on it.</p>
     */
    private void openTextDialogueIfListening(boolean openAllowed) {
        KeyMapping textKey = textInputKeySupplier.get();
        Minecraft client = Minecraft.getInstance();
        if (textKey == null || client == null || client.screen != null) {
            return;
        }
        while (textKey.consumeClick()) {
            if (openAllowed && InputSignals.hasSubscribers(InputSignals.TEXT_SUBMITTED)) {
                client.setScreen(new TextDialogueScreen());
            }
        }
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
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
