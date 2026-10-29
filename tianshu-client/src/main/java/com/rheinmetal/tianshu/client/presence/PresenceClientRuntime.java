package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.presence.capture.PresenceContextSnapshotAssembler;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.context.PresenceInteractionContextWatcher;
import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerStore;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.List;
import java.util.Objects;

/**
 * The client half of presence: one instance per client, not per world.
 *
 * <p>It owns the world-session boundary and the two things that outlive a world — the product-activity
 * aggregator and the query coordinator — and it is the only place that knows a world session is active. The
 * HUD reads {@link #currentHudDisplay()} on the render thread; everything else runs on the client thread.</p>
 */
public final class PresenceClientRuntime {
    private final PresenceActivityTracker activityTracker = new PresenceActivityTracker();
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresenceInteractionContextWatcher interactionContextWatcher;
    private final PresencePartnerStore partnerStore = new PresencePartnerStore();
    private final PresenceEventCollector eventCollector;
    private volatile PresenceActivitySnapshot displayedActivitySnapshot;
    private volatile PresenceHudDisplay cachedHudDisplay = PresenceHudDisplay.HIDDEN;
    private volatile boolean worldSessionActive;

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

    public void startWorldSession() {
        worldSessionActive = true;
        activityTracker.startWorldSession();
        contextQueryCoordinator.startWorldSession();
        interactionContextWatcher.reset();
        partnerStore.clear();
        eventCollector.startWorldSession();
    }

    public void stopWorldSession() {
        worldSessionActive = false;
        contextQueryCoordinator.stopWorldSession();
        interactionContextWatcher.reset();
        partnerStore.clear();
        eventCollector.stopWorldSession();
        activityTracker.stopWorldSession();
    }

    public TianshuModuleInstaller moduleInstaller(ModuleRuntimeAccess moduleRuntime) {
        PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(moduleRuntime);
        contextQueryCoordinator.bindResponder(adapter::respondContextFacts);
        interactionContextWatcher.setSink(adapter::publishInteractionContext);
        eventCollector.setWorldEventSink(adapter::publishWorldEvent);
        return new PresenceModuleInstaller(adapter, activityTracker, contextQueryCoordinator, partnerStore);
    }

    public void tick() {
        if (worldSessionActive) {
            contextQueryCoordinator.processPending();
            interactionContextWatcher.poll();
            eventCollector.flushWorldEvents();
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

    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        eventCollector.recordPlayerChatMessage(messageText, senderId, senderName);
    }

    public void recordWorldEvents(List<PresenceWorldEventPayload> events) {
        eventCollector.recordWorldEvents(events);
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
