package com.rheinmetal.tianshu.function.auxilium.event;

import java.text.Normalizer;
import java.util.Objects;

/**
 * Facts carried by one event occurrence.
 *
 * <p>Payload text comes from an arbitrary producer mod, so it is treated as untrusted input: it is
 * NFC normalized, stripped of angle brackets (a mod must not be able to inject {@code <event>} or
 * any other prompt tag through an event line) and truncated on a word boundary so one history line
 * stays bounded.
 *
 * @param text      short player-facing sentence describing what happened; never blank
 * @param count     how many of the thing happened inside this one event; at least {@code 1}
 * @param magnitude optional integer metric whose meaning is owned by the kind (for example hearts
 *                  lost); {@code null} when the kind carries no numeric metric
 * @param ref       optional short identifier (item id, entity id, structure id); empty when unused
 */
public record AXEventPayload(String text, int count, Integer magnitude, String ref) {
    /**
     * Upper bound for {@link #text}. Mirrors the {@code ax.events.payload.maxTextChars} default in
     * the event design document; the value lives here because payload sanitation must not depend on
     * runtime configuration.
     */
    public static final int MAX_TEXT_CHARS = 60;

    public AXEventPayload {
        text = sanitize(text);
        count = Math.max(1, count);
        ref = ref == null ? "" : ref.trim();
    }

    public static AXEventPayload of(String text) {
        return new AXEventPayload(text, 1, null, "");
    }

    public static AXEventPayload of(String text, int count, Integer magnitude) {
        return new AXEventPayload(text, count, magnitude, "");
    }

    private static String sanitize(String raw) {
        Objects.requireNonNull(raw, "event payload text is required");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();
        StringBuilder stripped = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch != '<' && ch != '>') {
                stripped.append(ch);
            }
        }
        String cleaned = stripped.toString().trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("event payload text is required");
        }
        return truncate(cleaned);
    }

    /**
     * Truncates on the last separator before the cap so a word or clause is not cut in half, and
     * never splits a surrogate pair.
     */
    private static String truncate(String value) {
        if (value.length() <= MAX_TEXT_CHARS) {
            return value;
        }
        int cut = MAX_TEXT_CHARS;
        if (Character.isLowSurrogate(value.charAt(cut))) {
            cut--;
        }
        for (int i = cut; i > MAX_TEXT_CHARS / 2; i--) {
            if (isSeparator(value.charAt(i - 1))) {
                return value.substring(0, i).trim();
            }
        }
        return value.substring(0, cut).trim();
    }

    private static boolean isSeparator(char ch) {
        return Character.isWhitespace(ch) || "，。、；：！？,.!?;:".indexOf(ch) >= 0;
    }
}
