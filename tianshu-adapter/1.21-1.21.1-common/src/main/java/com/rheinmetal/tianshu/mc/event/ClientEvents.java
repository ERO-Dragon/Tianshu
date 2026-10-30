package com.rheinmetal.tianshu.mc.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.api.assembly.RuntimeGates;
import com.rheinmetal.tianshu.api.assembly.TickSignals;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.mc.host.UiHost;
import com.rheinmetal.tianshu.mc.host.WorldIdentityCapture;
import com.rheinmetal.tianshu.mc.ui.PauseMenuConsoleSlot;
import com.rheinmetal.tianshu.mc.ui.TextDialogueScreen;
import com.rheinmetal.tianshu.mc.ui.hud.HudLayerHost;
import com.rheinmetal.tianshu.mc.ui.settings.TianshuSettingsModule;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What the host does when the game reports something.
 *
 * <p>Every method here is a decision that does not depend on which loader delivered the news: a session starts,
 * a tick happens, a screen is built, a frame is drawn, a chat line arrives. The loader adapter's only job is to
 * turn its own callback into one of these calls, which is why the adapter classes are dozens of lines while this
 * one is the whole policy.</p>
 *
 * <p>The one structural fact this class relies on is that a pause screen is a vanilla {@link Screen} holding
 * vanilla {@link Button}s: the loader only supplies the act of adding a widget, passed in as {@code addWidget}.</p>
 */
public final class ClientEvents {
    /** Vanilla's pause-menu grid buttons are 120 wide; the full-width entries below the grid are 200. */
    private static final int GRID_BUTTON_MAX_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private final LogSink logSink;
    private final TianshuCoreManager coreManager;
    private final TianshuSettingsModule settingsModule;
    private final ClientLifecycleAdapter lifecycleAdapter;
    private final HudLayerHost hudHost;
    private final WorldIdentityCapture worldIdentityCapture;
    private final Supplier<KeyMapping> voiceKeySupplier;
    private final Supplier<KeyMapping> textInputKeySupplier;
    private final DeclaredKeyBindings keyBindings;
    private final UiHost uiHost;
    private final ServerActionStatus serverActions;
    private final Supplier<String> protocolVersion;
    private final VoiceInputController voiceInputController = new VoiceInputController();
    private boolean sessionSummaryLogged;

    public ClientEvents(
            TianshuCoreManager coreManager,
            TianshuSettingsModule settingsModule,
            ClientLifecycleAdapter lifecycleAdapter,
            HudLayerHost hudHost,
            WorldIdentityCapture worldIdentityCapture,
            Supplier<KeyMapping> voiceKeySupplier,
            Supplier<KeyMapping> textInputKeySupplier,
            DeclaredKeyBindings keyBindings,
            UiHost uiHost,
            ServerActionStatus serverActions,
            Supplier<String> protocolVersion,
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
        this.serverActions = Objects.requireNonNull(serverActions, "serverActions");
        this.protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    public void onWorldLogin() {
        logSink.info("platform.world.login");
        worldIdentityCapture.refresh();
        WorldEventHooks.resetWorldSession();
        lifecycleAdapter.onWorldLogin();
    }

    public void onPlayerClone() {
        worldIdentityCapture.refresh();
    }

    public void onWorldLogout() {
        logSink.info("platform.world.logout");
        sessionSummaryLogged = false;
        resetVoiceInputState();
        worldIdentityCapture.clear();
        hudHost.resetLayers();
        WorldEventHooks.resetWorldSession();
        serverActions.reset();
        // The gate is global state written by a module; leaving it latched would swallow the next session's keys.
        // Only the gate is cleared here: the signal buses belong to the modules that subscribed to them.
        RuntimeGates.clear();
        lifecycleAdapter.onWorldLogout();
    }

    public void onClientTick() {
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
        TickSignals.publish(TickSignals.TICK, System.currentTimeMillis());

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
                && !RuntimeGates.inputBlocked();
    }

    /**
     * One line that answers "is the host actually running the session": modules loaded, the server's action
     * catalog and the wire protocol. It goes to the shared diagnostics log, so a report has this without the
     * player having to describe the setup.
     */
    private void logSessionSummary() {
        StringBuilder modules = new StringBuilder();
        for (TianshuManagedModule module : coreManager.managedModules()) {
            if (!modules.isEmpty()) {
                modules.append(',');
            }
            modules.append(module.moduleId());
        }
        logSink.info("platform.session.summary modules=[" + modules + "] actions="
                + serverActions.enabledActionsSnapshot()
                + " catalogKnown=" + serverActions.catalogReceivedSnapshot()
                + " protocol=" + protocolVersion.get());
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

    /**
     * Puts the console entry on the pause screen, at the bottom, without moving anything vanilla built.
     *
     * @param addWidget how this loader adds a widget to a screen; the only loader-specific input
     */
    public void onScreenInit(Screen screen, Consumer<Button> addWidget) {
        if (!(screen instanceof PauseScreen)) {
            return;
        }
        List<Button> narrow = new ArrayList<>();
        List<Button> wide = new ArrayList<>();
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
            addWidget.accept(Button.builder(
                    Component.translatable("tianshu.gui.settings.console"),
                    button -> settingsModule.openScreen()
            ).pos((screen.width - width) / 2, screen.height / 2).size(width, BUTTON_HEIGHT).build());
            return;
        }
        logSink.info("platform.pause.console_slot x=" + slot.x() + " y=" + slot.y()
                + " width=" + slot.width() + " grid=" + narrow.size() + " wide=" + wide.size()
                + " screenHeight=" + screen.height);
        addWidget.accept(Button.builder(
                Component.translatable("tianshu.gui.settings.console"),
                button -> settingsModule.openScreen()
        ).pos(slot.x(), slot.y()).size(slot.width(), BUTTON_HEIGHT).build());
    }

    private static List<PauseMenuConsoleSlot.Bounds> boundsOf(List<Button> buttons) {
        List<PauseMenuConsoleSlot.Bounds> bounds = new ArrayList<>(buttons.size());
        for (Button button : buttons) {
            bounds.add(new PauseMenuConsoleSlot.Bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
        }
        return bounds;
    }

    public void onRenderGui(GuiGraphics graphics) {
        hudHost.render(graphics);
    }

    /**
     * A player's chat line is a world event, not a second channel.
     *
     * <p>The line only becomes an event when there is something to report — a blank message or a missing sender is
     * not news, and letting it through would put an empty group in the consumer's window.</p>
     */
    public void onChatMessage(String messageText, String senderId, String senderName) {
        if (messageText == null || messageText.isBlank() || senderId == null || senderId.isBlank()) {
            return;
        }
        WorldEventHooks.publish(WorldEventPayload.chatMessage(
                senderId,
                senderName == null ? "" : senderName,
                messageText
        ));
    }

    public void resetVoiceInputState() {
        voiceInputController.reset();
    }
}
