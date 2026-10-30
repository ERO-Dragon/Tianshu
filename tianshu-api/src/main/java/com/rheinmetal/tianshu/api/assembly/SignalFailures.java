package com.rheinmetal.tianshu.api.assembly;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The failure boundary of the platform's signal buses.
 *
 * <p>{@link InputSignals} and {@link TickSignals} publish synchronously on the thread the platform
 * happens to be on — the client tick, an input callback, a screen callback. A subscriber that throws
 * therefore used to throw <em>into the game loop</em>, which turned one module's bug into a client
 * crash and silenced every subscriber after it on the same channel.</p>
 *
 * <p>Swallowing the exception is not an option either: a bus that hides failures is a bus nobody can
 * debug. So the buses report here, and the platform decides where that goes. The default handler does
 * nothing, which keeps api free of platform logging; a host installs a handler that writes to its own
 * diagnostics. A failing handler must never take the bus down again, so a throw from the handler is
 * contained as well.</p>
 *
 * <p>The counters exist for the two callers that ask "is anything going wrong?" without wiring a
 * handler: diagnostics and tests.</p>
 */
public final class SignalFailures {

    /** Receives a contained subscriber failure. Implementations must be cheap and must not throw. */
    @FunctionalInterface
    public interface Handler {
        Handler NOOP = (bus, channel, failure) -> {
        };

        void onSubscriberFailure(String bus, String channel, RuntimeException failure);
    }

    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile Handler handler = Handler.NOOP;

    private SignalFailures() {
    }

    /** Installs the process-wide reporter; {@code null} restores the silent default. */
    public static void setHandler(Handler next) {
        handler = next == null ? Handler.NOOP : next;
    }

    /** How many subscriber failures have been contained since the process started. */
    public static long failureCount() {
        return FAILURES.get();
    }

    static void report(String bus, String channel, RuntimeException failure) {
        FAILURES.incrementAndGet();
        try {
            handler.onSubscriberFailure(bus, channel, failure);
        } catch (RuntimeException ignored) {
            // A reporter that fails is still a reporter; the bus keeps serving the next subscriber.
        }
    }

    /** Test seam: restores the silent default and resets the counter. */
    static void resetForTests() {
        handler = Handler.NOOP;
        FAILURES.set(0L);
    }
}
