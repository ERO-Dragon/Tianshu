package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One event submitted to the layer, exactly as a mod hands it over.
 *
 * <p>{@code tier} is optional: when it is {@code null} the dispatcher consults the built-in tier
 * table and falls back to {@link AXEventTier#LOW} when the kind is unknown. {@code suppress} forces
 * the blacklist flag, which only removes the speech permission and never removes the history entry
 * (the sole exception is {@code suppress} combined with an effective low tier, which drops the event
 * at the entry).
 */
public record AXEventDescriptor(String kind, AXEventTier tier, boolean suppress, AXEventPayload payload) {
    /** Namespace plus local name, lowercase, colon separated; see kind discipline in the design doc. */
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public AXEventDescriptor {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("event kind is required");
        }
        kind = kind.trim();
        if (!KIND_PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("event kind must be namespaced, for example tianshu:death: " + kind);
        }
        Objects.requireNonNull(payload, "event payload is required");
    }

    /** Submits an event whose tier comes from the built-in table. */
    public static AXEventDescriptor of(String kind, AXEventPayload payload) {
        return new AXEventDescriptor(kind, null, false, payload);
    }

    /** Submits an event that declares its own tier. */
    public static AXEventDescriptor of(String kind, AXEventTier tier, AXEventPayload payload) {
        return new AXEventDescriptor(kind, tier, false, payload);
    }
}
