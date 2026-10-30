package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.neoforge.ui.PauseMenuConsoleSlot;
import com.rheinmetal.tianshu.neoforge.ui.TextDialogueScreen;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeClientUiHost;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeWorldIdentityCapture;
import com.rheinmetal.tianshu.neoforge.ui.hud.NeoForgeHudHost;
import com.rheinmetal.tianshu.neoforge.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
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
    /** Vanilla's pause-menu grid buttons are 120 wide; the full-width entries below the grid are 200. */
    private static final int GRID_BUTTON_MAX_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private final LogSink logSink;
    private final TianshuCoreManager coreManager;
    private final TianshuSettingsModule settingsModule;
    private final NeoForgeClientLifecycleAdapter lifecycleAdapter;
    private final NeoForgeHudHost hudHost;
    private final NeoForgeWorldIdentityCapture worldIdentityCapture;
    private final Supplier<KeyMapping> voiceKeySupplier;
    private final Supplier<KeyMapping> textInputKeySupplier;
    private final NeoForgeKeyBindings keyBindings;
    private final NeoForgeClientUiHost uiHost;
    private final NeoForgeVoiceInputController voiceInputController;
    private boolean sessionSummaryLogged;

    public NeoForgeClientEvents(
            TianshuCoreManager coreManager,
            TianshuSettingsModule settingsModule,
            NeoForgeClientLifecycleAdapter lifecycleAdapter,
            NeoForgeHudHost hudHost,
            NeoForgeWorldIdentityCapture worldIdentityCapture,
            Supplier<KeyMapping> voiceKeySupplier,
            Supplier<KeyMapping> textInputKeySupplier,
            NeoForgeKeyBindings keyBindings,
            NeoForgeClientUiHost uiHost,
            LogSink logSink
    ) {
        this.coreManager = Objects.requireNonNull(coreManager, "coreManager");
        this.settingsModule = Objects.requireNonNull(settingsModule, "settingsModule");
        this.lifecycleAdapter = Objects.requireNonNull(lifecycleAdapter, "lifecycleAdapter");
        this.hudHost = Objects.requireNonNull(hudHost, "hudHost");
        this.worldIdentityCapture = Objects.requireNonNull(worldIdentityCapture, "worldIdentityCapture");
        this.voiceKeySupplier = Objects.requireNonNull(voiceKeySupplier, "voiceKeySupplier");
        this.textInputKeySupplier = Objects.requireNonNull(textInputKeySupplier, "textInputKeySupplier");
        this.keyBindings = Objects.requireNonNull(keyBindings, "keyBindings");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.voiceInputController = new NeoForgeVoiceInputController();
    }

    @SubscribeEvent
    public void onWorldLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        logSink.info("neoforge.world.login");
        worldIdentityCapture.refresh();
        NeoForgeWorldEventHooks.resetWorldSession();
        lifecycleAdapter.onWorldLogin();
    }

    @SubscribeEvent
    public void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        worldIdentityCapture.refresh();
    }

    @SubscribeEvent
    public void onWorldLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        logSink.info("neoforge.world.logout");
        sessionSummaryLogged = false;
        resetVoiceInputState();
        worldIdentityCapture.clear();
        hudHost.resetLayers();
        NeoForgeWorldEventHooks.resetWorldSession();
        com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient.reset();
        // The gate is global state written by a module; leaving it latched would swallow the next session's keys.
        // Only the gate is cleared here: the signal buses belong to the modules that subscribed to them.
        com.rheinmetal.tianshu.api.assembly.RuntimeGates.clear();
        lifecycleAdapter.onWorldLogout();
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (!sessionSummaryLogged && coreManager.isInitialized()) {
            sessionSummaryLogged = true;
            logSessionSummary();
        }
        if (!worldIdentityCapture.isAvailable()) {
            worldIdentityCapture.refresh();
        }
        // The event layer owns two time-based doors (the deferred window and the immediate queue); this is the clock
        // that opens them. Modules that need a per-tick clock subscribe to it themselves.
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
     * <p>Two gates, one reading: the core must be running the session, and the runtime gate must not be reporting
     * "loading". The gate is written by the module that aggregates the product state, so this is the same answer
     * the HUD shows — the platform never inspects an individual module's capabilities, and never names the module
     * that produces the state.</p>
     */
    private boolean moduleInputAvailable() {
        return coreManager.isInitialized()
                && coreManager.runtimeReadyForRequests()
                && !com.rheinmetal.tianshu.api.assembly.RuntimeGates.inputBlocked();
    }

    /**
     * One line that answers "is the host actually running the session": modules loaded, the server's action
     * catalog and the wire protocol. It goes to the shared diagnostics log, so a report has this without the
     * player having to describe the setup.
     */
    private void logSessionSummary() {
        StringBuilder modules = new StringBuilder();
        for (com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule module : coreManager.managedModules()) {
            if (!modules.isEmpty()) {
                modules.append(',');
            }
            modules.append(module.moduleId());
        }
        logSink.info("neoforge.session.summary modules=[" + modules + "] actions="
                + com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient
                        .enabledActionsSnapshot()
                + " catalogKnown="
                + com.rheinmetal.tianshu.neoforge.adapter.NeoForgeServerActionClient.catalogReceivedSnapshot()
                + " protocol=" + com.rheinmetal.tianshu.neoforge.network.TianshuNetwork.protocolVersion());
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
                client.setScreen(new TextDialogueScreen(uiHost.textInputTitle(), uiHost.textInputHint()));
            }
        }
    }

    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof PauseScreen)) {
            return;
        }

        java.util.List<Button> narrow = new java.util.ArrayList<>();
        java.util.List<Button> wide = new java.util.ArrayList<>();
        for (GuiEventListener widget : screen.children()) {
            if (widget instanceof Button button) {
                // PauseScreen's two-column grid uses narrow buttons; everything below it ("Mods", "Save and Quit")
                // spans the full width. The width is the only thing that tells the two apart.
                (button.getWidth() <= GRID_BUTTON_MAX_WIDTH ? narrow : wide).add(button);
            }
        }
        PauseMenuConsoleSlot.Bounds slot = PauseMenuConsoleSlot.resolve(
                boundsOf(narrow), boundsOf(wide), BUTTON_HEIGHT, BUTTON_GAP);
        if (slot == null) {
            // No grid to anchor to: keep an entry so a modded pause menu still reaches the console.
            int width = GRID_BUTTON_MAX_WIDTH;
            event.addListener(Button.builder(
                    Component.translatable("tianshu.gui.settings.console"),
                    button -> settingsModule.openScreen()
            ).pos((screen.width - width) / 2, screen.height / 2).size(width, BUTTON_HEIGHT).build());
            return;
        }
        logSink.info("neoforge.pause.console_slot x=" + slot.x() + " y=" + slot.y()
                + " width=" + slot.width() + " grid=" + narrow.size() + " wide=" + wide.size()
                + " screenHeight=" + screen.height);
        event.addListener(Button.builder(
                Component.translatable("tianshu.gui.settings.console"),
                button -> settingsModule.openScreen()
        ).pos(slot.x(), slot.y()).size(slot.width(), BUTTON_HEIGHT).build());
    }

    private static java.util.List<PauseMenuConsoleSlot.Bounds> boundsOf(java.util.List<Button> buttons) {
        java.util.List<PauseMenuConsoleSlot.Bounds> bounds = new java.util.ArrayList<>(buttons.size());
        for (Button button : buttons) {
            bounds.add(new PauseMenuConsoleSlot.Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
        }
        return bounds;
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        hudHost.render(event.getGuiGraphics());
    }

    /**
     * A player's chat line is a world event, not a second channel.
     *
     * <p>The line only becomes an event when there is something to report — a blank message or a missing sender is
     * not news, and letting it through would put an empty group in the consumer's window.</p>
     */
    @SubscribeEvent
    public void onClientChatReceived(ClientChatReceivedEvent event) {
        if (!(event instanceof ClientChatReceivedEvent.Player) || event.isSystem()) {
            return;
        }
        String messageText = event.getMessage().getString();
        String senderId = event.getSender() == null ? "" : event.getSender().toString();
        if (messageText.isBlank() || senderId.isBlank()) {
            return;
        }
        NeoForgeWorldEventHooks.publish(WorldEventPayload.chatMessage(
                senderId,
                playerChatSenderName(event),
                messageText
        ));
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
