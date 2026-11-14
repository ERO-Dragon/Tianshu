package com.rheinmetal.tianshu.api.capability;

import java.util.Objects;

/**
 * What a capability is <em>for</em>: how an orchestrator consumes it.
 *
 * <p>The set is open. These constants are the kinds the kernel understands today; a provider may register another
 * kind and a consumer that understands it can use it. Unknown kinds are preserved and never dropped.</p>
 */
public record CapabilityKind(String value) implements Comparable<CapabilityKind> {

    public static final CapabilityKind TOOL = new CapabilityKind("tool");
    public static final CapabilityKind SKILL = new CapabilityKind("skill");
    public static final CapabilityKind RESOURCE = new CapabilityKind("resource");
    public static final CapabilityKind PROMPT = new CapabilityKind("prompt");
    public static final CapabilityKind CONNECTOR = new CapabilityKind("connector");

    public CapabilityKind {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a capability kind must not be blank");
        }
        value = trimmed;
    }

    public static CapabilityKind of(String value) {
        return new CapabilityKind(value);
    }

    public boolean isTool() {
        return TOOL.equals(this);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(CapabilityKind other) {
        return value.compareTo(other.value);
    }
}
