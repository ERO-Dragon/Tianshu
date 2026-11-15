package com.rheinmetal.tianshu.api.capability;

import com.rheinmetal.tianshu.api.IdSegments;

import java.util.Objects;

/**
 * A capability's stable identity: {@code namespace:name}.
 *
 * <p>Same shape as a tool id on purpose: a capability table has to hold tools, skills and everything that comes
 * later under one namespace, or two providers will silently shadow each other. The grammar itself lives in
 * {@link IdSegments}, shared with {@link com.rheinmetal.tianshu.api.tool.ToolId}, so the two families cannot drift
 * apart into accepting different strings.</p>
 */
public record CapabilityId(String namespace, String name) implements Comparable<CapabilityId> {

    private static final String KIND = "capability id";

    public CapabilityId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        IdSegments.require(namespace, KIND, "namespace");
        IdSegments.require(name, KIND, "name");
    }

    public static CapabilityId of(String namespace, String name) {
        return new CapabilityId(namespace, name);
    }

    public static CapabilityId parse(String text) {
        String[] parts = IdSegments.split(text, KIND);
        return new CapabilityId(parts[0], parts[1]);
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }

    @Override
    public int compareTo(CapabilityId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : name.compareTo(other.name);
    }
}