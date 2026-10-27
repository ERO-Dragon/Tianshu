package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.host.ClientGameContextProvider;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.protocol.payload.PresenceInteractionContextPayload;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Publishes {@code PRESENCE.INTERACTION_CONTEXT} whenever the claim-relevant interaction changes.
 *
 * <p>It captures only the {@link com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup#INTERACTION_CONTEXT}
 * group — the crosshair target, held and equipped items, interaction keys and the open screen. Every other
 * group (inventory, effects, environment) stays out of this path, so the per-tick cost is reading values the
 * client already has, and a push only leaves when the comparison says the situation really changed.</p>
 *
 * <p>A failing platform read is swallowed: a broken capture must degrade this channel, not the client tick.
 * Leaving the last published value in place means the arbiter keeps its previous pre-winner instead of
 * flapping to "nothing".</p>
 */
public final class PresenceInteractionContextWatcher {
    private final ClientGameContextProvider platform;
    private PresenceInteractionContextSink sink = PresenceInteractionContextSink.NOOP;
    private PresenceInteractionContextPayload lastPublished;

    public PresenceInteractionContextWatcher(ClientGameContextProvider platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public void setSink(PresenceInteractionContextSink sink) {
        this.sink = sink == null ? PresenceInteractionContextSink.NOOP : sink;
    }

    /** Forgets what was published, so the next {@link #poll()} publishes even when nothing changed. */
    public void reset() {
        lastPublished = null;
    }

    /** One client tick: capture, compare with the last push, publish on change. */
    public void poll() {
        PresenceInteractionContextPayload next;
        try {
            PresenceContextSnapshot snapshot = platform.captureContext(EnumSet.of(PresenceContextGroup.INTERACTION_CONTEXT));
            next = PresenceInteractionContextPayload.from(snapshot);
        } catch (RuntimeException failure) {
            return;
        }
        if (next.playerId().isBlank()) {
            return;
        }
        if (lastPublished != null && lastPublished.sameInteraction(next)) {
            return;
        }
        lastPublished = next;
        sink.publish(next);
    }

    /** Where a changed interaction goes; the adapter binds it to the protocol topic. */
    @FunctionalInterface
    public interface PresenceInteractionContextSink {
        PresenceInteractionContextSink NOOP = payload -> {
        };

        void publish(PresenceInteractionContextPayload payload);
    }
}