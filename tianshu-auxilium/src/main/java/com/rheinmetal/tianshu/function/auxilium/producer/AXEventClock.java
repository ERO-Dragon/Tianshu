package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.api.assembly.TickSignals;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The event layer's clock: the platform's tick signal, forwarded to the running event service.
 *
 * <p>It exists because the layer owns time-based behaviour that nothing else can drive: the deferred cooldown window
 * ("say it during the next lull") and the release of the immediate queue once the assistant is free again. Those only
 * fire when something advances the clock, and the clock used to arrive with the sampling port that has since been
 * retired — deleting the fact source must not delete the clock.</p>
 *
 * <p>The platform side is {@link TickSignals} (api), which carries no facts, only "time moved". This holder is the
 * one subscriber: it forwards to whichever service is installed, so a tick after a session ended is harmless.</p>
 */
public final class AXEventClock {

    private static final AtomicReference<AXGameEventService> SERVICE = new AtomicReference<>();

    static {
        TickSignals.subscribe(TickSignals.TICK, AXEventClock::tick);
    }

    private AXEventClock() {
    }

    static void install(AXGameEventService service) {
        if (service != null) {
            SERVICE.set(service);
        }
    }

    static void clear(AXGameEventService service) {
        if (service != null) {
            SERVICE.compareAndSet(service, null);
        }
    }

    /** Advances the running event layer, if any; a platform tick without a session does nothing. */
    public static void tick(long nowMillis) {
        AXGameEventService service = SERVICE.get();
        if (service != null) {
            service.tick(nowMillis);
        }
    }
}