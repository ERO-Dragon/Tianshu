package com.rheinmetal.tianshu.api.capability;

import java.util.Objects;

/**
 * A capability's stable identity: {@code namespace:name}.
 *
 * <p>Same shape as a tool id on purpose: a capability table has to hold tools, skills and everything that comes
 * later under one namespace, or two providers will silently shadow each other.</p>
 */
public record CapabilityId(String namespace, String name) implements Comparable<CapabilityId> {

    public CapabilityId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        requireSegment(namespace, "namespace");
        requireSegment(name, "name");
    }

    public static CapabilityId of(String namespace, String name) {
        return new CapabilityId(namespace, name);
    }

    public static CapabilityId parse(String text) {
        Objects.requireNonNull(text, "text");
        int separator = text.indexOf(':');
        if (separator <= 0 || separator == text.length() - 1) {
            throw new IllegalArgumentException("a capability id is namespace:name, got: " + text);
        }
        return new CapabilityId(text.substring(0, separator), text.substring(separator + 1));
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

    private static void requireSegment(String value, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a capability id " + what + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "a capability id " + what + " may only contain a-z, 0-9, '_', '.' and '-': " + value);
            }
        }
    }
}
