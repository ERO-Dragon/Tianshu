package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
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
    private final PresenceEventCollector eventCollector;
    private volatile PresenceActivitySnapshot displayedActivitySnapshot;
    private volatile PresenceHudDisplay cachedHudDisplay = PresenceHudDisplay.HIDDEN;
    private volatile boolean worldSessionActive;

    public PresenceClientRuntime(ClientGameContextProvider platform, PresenceTextProvider textProvider) {
        this(platform, textProvider, LogSink.NOOP);
    }

    public PresenceClientRuntime(ClientGameContextProvider platform, PresenceTextProvider textProvider, LogSink logSink) {
        this.displayPolicy = new PresenceDisplayPolicy(textProvider);
        this.contextQueryCoordinator = new PresenceContextQueryCoordinator(
                Objects.requireNonNull(platform, "platform"),
                new PresenceContextFactMapper()
        );
        this.eventCollector = new PresenceEventCollector(logSink);
    }

    public void startWorldSession() {
        worldSessionActive = true;
        activityTracker.startWorldSession();
        contextQueryCoordinator.startWorldSession();
        eventCollector.startWorldSession();
    }

    public void stopWorldSession() {
        worldSessionActive = false;
        contextQueryCoordinator.stopWorldSession();
        eventCollector.stopWorldSession();
        activityTracker.stopWorldSession();
    }

    public TianshuModuleInstaller moduleInstaller(ModuleRuntimeAccess moduleRuntime) {
        PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(moduleRuntime);
        contextQueryCoordinator.bindResponder(adapter::respondContextFacts);
        eventCollector.setWorldEventSink(adapter::publishWorldEvent);
        return new PresenceModuleInstaller(adapter, activityTracker, contextQueryCoordinator);
    }

    public void tick() {
        if (worldSessionActive) {
            contextQueryCoordinator.processPending();
            eventCollector.flushWorldEvents();
        }
    }

    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        eventCollector.recordPlayerChatMessage(messageText, senderId, senderName);
    }

    public void recordWorldEvents(List<PresenceWorldEventPayload> events) {
        eventCollector.recordWorldEvents(events);
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
