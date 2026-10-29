package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.RuntimeGates;
import com.rheinmetal.tianshu.api.assembly.TickSignals;
import com.rheinmetal.tianshu.api.client.host.hud.HudHost;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.function.presence.capture.PresenceContextSnapshotAssembler;
import com.rheinmetal.tianshu.function.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.function.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.function.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.function.presence.context.PresenceInteractionContextWatcher;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudLayer;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.function.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerStore;
import com.rheinmetal.tianshu.function.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.function.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.function.presence.status.PresenceHudDisplay;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import java.util.Objects;

/**
 * The client half of presence: one instance per assembled session.
 *
 * <p>It owns the world-session boundary and everything that lives inside it — the activity aggregator, the query
 * coordinator, the partner store and the tick subscription — and it is the only place that knows the session is
 * active. The session is opened by the provider while the session is assembled and closed by the module when it
 * stops, so the runtime's lifetime and the module's are the same. The HUD reads {@link #currentHudDisplay()} on the
 * render thread; everything else runs on the client thread.</p>
 */
public final class PresenceClientRuntime {
    private static final TickSignals.Subscription NO_TICK = () -> {
    };

    private final PresenceActivityTracker activityTracker = new PresenceActivityTracker();
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresenceInteractionContextWatcher interactionContextWatcher;
    private final PresencePartnerStore partnerStore = new PresencePartnerStore();
    private final PresenceEventCollector eventCollector;
    private volatile PresenceActivitySnapshot displayedActivitySnapshot;
    private volatile PresenceHudDisplay cachedHudDisplay = PresenceHudDisplay.HIDDEN;
    private volatile boolean worldSessionActive;
    /** The clock subscription while a world session is running; presence is ticked by the shared clock signal. */
    private volatile TickSignals.Subscription tickSubscription = NO_TICK;
    /** Last value handed to {@link RuntimeGates}; the gate is written only on change. */
    private volatile boolean inputBlocked;

    public PresenceClientRuntime(ClientGameContextPort platform, PresenceTextProvider textProvider) {
        this(platform, textProvider, LogSink.NOOP);
    }

    public PresenceClientRuntime(ClientGameContextPort platform, PresenceTextProvider textProvider, LogSink logSink) {
        PresenceContextSnapshotAssembler assembler = new PresenceContextSnapshotAssembler();
        this.displayPolicy = new PresenceDisplayPolicy(textProvider);
        this.contextQueryCoordinator = new PresenceContextQueryCoordinator(
                Objects.requireNonNull(platform, "platform"),
                assembler,
                new PresenceContextFactMapper()
        );
        this.interactionContextWatcher = new PresenceInteractionContextWatcher(platform, assembler);
        this.eventCollector = new PresenceEventCollector(logSink);
    }

    /** Opens the world session; idempotent because the provider and the module both ensure it is open. */
    public void startWorldSession() {
        if (worldSessionActive) {
            return;
        }
        worldSessionActive = true;
        activityTracker.startWorldSession();
        contextQueryCoordinator.startWorldSession();
        interactionContextWatcher.reset();
        partnerStore.clear();
        eventCollector.startWorldSession();
        publishInputGate();
        subscribeToClock();
    }

    /** Closes the world session; idempotent for the same reason as {@link #startWorldSession()}. */
    public void stopWorldSession() {
        if (!worldSessionActive) {
            return;
        }
        worldSessionActive = false;
        unsubscribeFromClock();
        contextQueryCoordinator.stopWorldSession();
        interactionContextWatcher.reset();
        partnerStore.clear();
        eventCollector.stopWorldSession();
        activityTracker.stopWorldSession();
        publishInputGate();
    }

    /**
     * The module as the platform installs it.
     *
     * <p>The adapter is built by the provider, because presence registers its own protocol vocabulary (topics and
     * payload kinds) while the session is assembled, before any module can publish. The installer still owns the HUD
     * layer's lifetime: the layer appears when the module registers and disappears when it stops, so a disabled module
     * costs the render loop nothing at all.</p>
     */
    public TianshuModuleInstaller moduleInstaller(
            PresenceProtocolAdapter adapter,
            HudHost hud,
            PresenceHudSettings settings
    ) {
        contextQueryCoordinator.bindResponder(adapter::respondContextFacts);
        interactionContextWatcher.setSink(adapter::publishInteractionContext);
        eventCollector.setWorldEventSink(adapter::publishWorldEvent);
        HudLayer layer = hudLayer(settings);
        return new PresenceModuleInstaller(adapter, activityTracker, contextQueryCoordinator, partnerStore,
                this::recordExternalEvent, this, hud, layer);
    }

    /**
     * The HUD as one platform layer.
     *
     * <p>The runtime owns the two suppliers — the cached display and the partner marker — and the platform decides where
     * the layer is registered. Presence therefore never needs to know the platform's render loop, and the platform never
     * needs to know what a frame contains.</p>
     */
    public PresenceHudLayer hudLayer(PresenceHudSettings settings) {
        return new PresenceHudLayer(
                settings,
                this::currentHudDisplay,
                () -> currentPartner(System.currentTimeMillis())
        );
    }

    /**
     * Feeds one raw platform event into the aggregation window.
     *
     * <p>The platform produces single occurrences (and knows only {@code WorldEventTypes}); the window, the
     * grouping and the re-publication are presence's, which is why the conversion lives on the presence side and
     * the host never sees an aggregated payload.</p>
     */
    public void recordExternalEvent(WorldEventPayload event) {
        eventCollector.recordWorldEvent(event);
    }

    public void tick() {
        if (worldSessionActive) {
            contextQueryCoordinator.processPending();
            interactionContextWatcher.poll();
            eventCollector.flushWorldEvents();
            publishInputGate();
        }
    }

    /**
     * Keeps the platform's input gate in step with the aggregated product state.
     *
     * <p>The platform must not ask presence whether it is loading — that would name the module — so presence pushes
     * the answer into {@link RuntimeGates} instead. It is written only when the answer changes, so a steady state
     * costs nothing.</p>
     */
    private void publishInputGate() {
        boolean blocked = isLoading();
        if (blocked != inputBlocked) {
            inputBlocked = blocked;
            RuntimeGates.setInputBlocked(blocked);
        }
    }

    private void subscribeToClock() {
        unsubscribeFromClock();
        tickSubscription = TickSignals.subscribe(TickSignals.TICK, ignored -> tick());
    }

    private void unsubscribeFromClock() {
        TickSignals.Subscription subscription = tickSubscription;
        tickSubscription = NO_TICK;
        if (subscription != null) {
            subscription.close();
        }
    }

    /**
     * Whether the player-visible state is "loading": some module is really starting up and cannot serve yet.
     *
     * <p>The platform reads this to keep module keys unavailable during startup, so the gate and the HUD can never
     * disagree — both come from the same aggregated state.</p>
     */
    public boolean isLoading() {
        return worldSessionActive && activityTracker.snapshot().primaryState() == PresencePrimaryState.LOADING;
    }

    /**
     * The partner marker to draw at {@code nowMillis}.
     *
     * <p>Only the ring is resolved against the clock here. Whether the icon is on screen at all stays the
     * arbiter's decision — see {@link PresencePartnerStore#at(long)} — so a lease running out never makes the
     * marker fly off on its own. Nothing is drawn outside a world session.</p>
     */
    public PresencePartnerSnapshot currentPartner(long nowMillis) {
        return worldSessionActive ? partnerStore.at(nowMillis) : PresencePartnerSnapshot.HIDDEN;
    }

    /**
     * The immutable snapshot the HUD draws. The mapping from product state to text runs only when the
     * aggregated snapshot object changes, never per frame.
     */
    public PresenceHudDisplay currentHudDisplay() {
        if (!worldSessionActive) {
            return PresenceHudDisplay.HIDDEN;
        }
        PresenceActivitySnapshot snapshot = activityTracker.snapshot();
        if (snapshot != displayedActivitySnapshot) {
            cachedHudDisplay = displayPolicy.hudDisplay(snapshot);
            displayedActivitySnapshot = snapshot;
        }
        return cachedHudDisplay;
    }
}
