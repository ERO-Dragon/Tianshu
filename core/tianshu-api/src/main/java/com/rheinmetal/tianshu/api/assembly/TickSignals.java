package com.rheinmetal.tianshu.api.assembly;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/**
 * Clock signal: the platform states that time moved, modules decide what to do with it.
 *
 * <p>Same shape and same direction as {@link InputSignals}: the platform publishes, modules subscribe, and neither
 * side names the other. It exists because a module may own time-based behaviour that only a tick can advance — the
 * assistant's event layer has two such doors (a deferred window and a queue that is released once the assistant is
 * free again) and nothing else would ever open them.</p>
 *
 * <p>It carries no facts, only the clock reading: a subscriber that wants to sniff the world would have to ask for it
 * elsewhere, which is the point.</p>
 */
public final class TickSignals {

    /** The channel name; there is one clock, so there is one channel. */
    public static final String TICK = "tick";

    /** Cancel a subscription by closing it; closing twice is a no-op. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final Map<String, List<LongConsumer>> HANDLERS = new ConcurrentHashMap<>();

    private TickSignals() {
    }

    public static Subscription subscribe(String channel, LongConsumer handler) {
        if (channel == null || channel.isBlank() || handler == null) {
            return () -> {
            };
        }
        List<LongConsumer> handlers = HANDLERS.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    /** Publishes one clock reading to whoever is subscribed right now. Publishing to nobody is normal. */
    public static void publish(String channel, long nowMillis) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        List<LongConsumer> handlers = HANDLERS.get(channel);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        for (LongConsumer handler : handlers) {
            try {
                handler.accept(nowMillis);
            } catch (RuntimeException failure) {
                // A subscriber that throws runs on the client tick; contain it here (see SignalFailures).
                SignalFailures.report("tick", channel, failure);
            }
        }
    }

    /** Drops every subscription; session teardown starts from a clean bus and tests use it too. */
    public static void clear() {
        HANDLERS.clear();
    }
}