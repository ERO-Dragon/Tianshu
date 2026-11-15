package com.rheinmetal.tianshu.api.tool;

import com.rheinmetal.tianshu.api.IdSegments;

import java.util.Objects;

/**
 * A tool's stable identity: a namespace and a name, written {@code namespace:name}.
 *
 * <p>The namespace is mandatory on purpose. Tools are registered by several parties (the kernel, each
 * feature module and third-party mods), and a bare name would make a collision a load-order accident instead
 * of a build error.</p>
 */
public record ToolId(String namespace, String name) implements Comparable<ToolId> {

    private static final String KIND = "tool id";

    public ToolId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        IdSegments.require(namespace, KIND, "namespace");
        IdSegments.require(name, KIND, "name");
    }

    public static ToolId of(String namespace, String name) {
        return new ToolId(namespace, name);
    }

    /** Parses {@code namespace:name}; the colon is mandatory. */
    public static ToolId parse(String text) {
        String[] parts = IdSegments.split(text, KIND);
        return new ToolId(parts[0], parts[1]);
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }

    @Override
    public int compareTo(ToolId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : name.compareTo(other.name);
    }
}