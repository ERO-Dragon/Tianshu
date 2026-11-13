package com.rheinmetal.tianshu.api.capability;

import java.util.Objects;

/**
 * How a capability is reached: the "last mile" a provider implements.
 *
 * <p>It is deliberately independent of {@link CapabilityKind}: an MCP server may expose tools, resources and prompts,
 * and a skill may be reached locally or over MCP.</p>
 */
public record CapabilityBinding(String value) implements Comparable<CapabilityBinding> {

    public static final CapabilityBinding LOCAL = new CapabilityBinding("local");
    public static final CapabilityBinding MCP = new CapabilityBinding("mcp");
    public static final CapabilityBinding HTTP = new CapabilityBinding("http");
    public static final CapabilityBinding COMMAND = new CapabilityBinding("command");

    public CapabilityBinding {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a capability binding must not be blank");
        }
        value = trimmed;
    }

    public static CapabilityBinding of(String value) {
        return new CapabilityBinding(value);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public int compareTo(CapabilityBinding other) {
        return value.compareTo(other.value);
    }
}
