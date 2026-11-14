package com.rheinmetal.tianshu.api.tool;

import java.util.Objects;

/**
 * A tool's stable identity: a namespace and a name, written {@code namespace:name}.
 *
 * <p>The namespace is mandatory on purpose. Tools are registered by several parties (the kernel, each
 * feature module and third-party mods), and a bare name would make a collision a load-order accident instead
 * of a build error.</p>
 */
public record ToolId(String namespace, String name) implements Comparable<ToolId> {

    public ToolId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        requireSegment(namespace, "namespace");
        requireSegment(name, "name");
    }

    public static ToolId of(String namespace, String name) {
        return new ToolId(namespace, name);
    }

    /** Parses {@code namespace:name}; the colon is mandatory. */
    public static ToolId parse(String text) {
        Objects.requireNonNull(text, "text");
        int separator = text.indexOf(':');
        if (separator <= 0 || separator == text.length() - 1) {
            throw new IllegalArgumentException("a tool id is namespace:name, got: " + text);
        }
        return new ToolId(text.substring(0, separator), text.substring(separator + 1));
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

    private static void requireSegment(String value, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a tool id " + what + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "a tool id " + what + " may only contain a-z, 0-9, '_', '.' and '-': " + value);
            }
        }
    }
}