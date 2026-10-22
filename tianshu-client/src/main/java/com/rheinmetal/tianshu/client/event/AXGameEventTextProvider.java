package com.rheinmetal.tianshu.client.event;

/**
 * Resolves the language keys that belong to this producer.
 *
 * <p>The event layer holds no wording, so a producer that wants to report an event has to render the
 * sentence itself; this interface is the only door to a language table and the implementation lives on
 * the platform side, where the client language files are addressed.
 *
 * <p>A key that is missing resolves to an empty string instead of the key itself: an unresolved key is
 * never allowed to leak into the history, and an empty sentence means "this producer cannot word it",
 * which is the signal to fall back to a plain value or to skip the event.
 */
public interface AXGameEventTextProvider {
    /** Provider used when a platform has not wired a language table yet: never renders anything. */
    AXGameEventTextProvider NONE = new AXGameEventTextProvider() {
        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public String text(String key, Object... args) {
            return "";
        }
    };

    boolean exists(String key);

    /** Resolved sentence, or an empty string when the key is unknown. */
    String text(String key, Object... args);
}
