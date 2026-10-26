package com.rheinmetal.tianshu.api.assembly;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Input signals: the platform states what happened, modules decide whether they care.
 *
 * <p>Channels are plain strings on purpose. The built-in constants cover the gestures the kernel itself knows
 * about; a module that wants its own key declares it through {@link KeyBindings} and subscribes to
 * {@link #keyChannel(String)} — the kernel never grows a constant for a feature's key.</p>
 *
 * <p>The direction is the point: the platform publishes, modules subscribe, and neither side names the other.
 * A port implemented by a module and pulled by the platform would invert that.</p>
 *
 * <p>Subscription is explicit and revocable: a module subscribes while it is running and unsubscribes when it
 * stops, so a stopped module cannot react to a key press.</p>
 */
public final class InputSignals {

    /** The push-to-talk key went down. */
    public static final String PUSH_TO_TALK_DOWN = "push_to_talk.down";

    /** The push-to-talk key came up. */
    public static final String PUSH_TO_TALK_UP = "push_to_talk.up";

    /** The gesture was abandoned (screen opened, key cancelled). */
    public static final String PUSH_TO_TALK_CANCEL = "push_to_talk.cancel";

    /** The player submitted a line of text; the text travels with the signal. */
    public static final String TEXT_SUBMITTED = "text.submitted";

    private static final String KEY_CHANNEL_PREFIX = "key.";

    /** Cancel a subscription by closing it; closing twice is a no-op. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final Map<String, List<Consumer<String>>> HANDLERS = new ConcurrentHashMap<>();

    private InputSignals() {
    }

    /** The channel a declared key publishes on. Two keys with different ids never share a channel. */
    public static String keyChannel(String keyId) {
        String id = keyId == null ? "" : keyId.trim();
        return id.isEmpty() ? KEY_CHANNEL_PREFIX : KEY_CHANNEL_PREFIX + id;
    }

    /**
     * Subscribes to one channel.
     *
     * <p>The handler receives the signal's text, which is empty for a key press and carries the player's line
     * for {@link #TEXT_SUBMITTED}. Handlers run on the thread that published, so a module that needs to work
     * elsewhere schedules it itself.</p>
     */
    public static Subscription subscribe(String channel, Consumer<String> handler) {
        if (channel == null || channel.isBlank() || handler == null) {
            return () -> {
            };
        }
        List<Consumer<String>> handlers = HANDLERS.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>());
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }

    /**
     * Whether anyone is listening on this channel right now.
     *
     * <p>This is what lets the platform avoid offering an input nobody consumes: a module subscribes only
     * while its own setting allows it, so asking the bus is the same question as asking the module — without
     * the platform having to know the module exists.</p>
     */
    public static boolean hasSubscribers(String channel) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        List<Consumer<String>> handlers = HANDLERS.get(channel);
        return handlers != null && !handlers.isEmpty();
    }

    /** Publishes one signal to whoever is subscribed right now. Publishing to nobody is normal. */
    public static void publish(String channel, String text) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        List<Consumer<String>> handlers = HANDLERS.get(channel);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        String payload = text == null ? "" : text;
        for (Consumer<String> handler : handlers) {
            handler.accept(payload);
        }
    }

    /** Drops every subscription; session teardown starts from a clean bus and tests use it too. */
    public static void clear() {
        HANDLERS.clear();
    }
}
