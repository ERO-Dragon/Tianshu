package com.rheinmetal.tianshu.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wire vocabulary of payload kinds.
 *
 * <p>The constants below are the platform's own base kinds. Every feature kind belongs to the module that
 * produces it and is created there with {@link #of(String)} (or {@link #of(String, Class)}) — the kernel
 * neither names nor registers a feature's payload types, so adding or removing a module never edits this
 * class.</p>
 *
 * <p>Identity follows the name: {@link #of(String)} always returns the canonical instance, and two
 * instances with the same name are equal. A name may be bound to exactly one payload class; binding it to
 * a second class is rejected instead of failing later at message validation time.</p>
 */
public final class PayloadType {

    private static final Map<String, PayloadType> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, Class<? extends ITianshuPayload>> BY_CLASS_OF_NAME =
            new ConcurrentHashMap<>();

    public static final PayloadType NONE = register("NONE");
    /** Backed by {@code TextPayload}; exercised by the dialogue contract validator. */
    public static final PayloadType TEXT = register("TEXT");
    public static final PayloadType CANCEL = register("CANCEL");
    public static final PayloadType MODULE_STATUS = register("MODULE_STATUS");
    /** Escape hatch for module payloads that live outside the kernel's vocabulary. */
    public static final PayloadType CUSTOM = register("CUSTOM");

    private final String name;

    private PayloadType(String name) {
        this.name = name;
    }

    /** The canonical instance for {@code name}; an unknown name is registered on first use. */
    public static PayloadType of(String name) {
        return register(requireName(name));
    }

    /**
     * The canonical instance for {@code name}, bound to {@code payloadClass}.
     *
     * @throws IllegalStateException when the name is already bound to a different payload class
     */
    public static PayloadType of(String name, Class<? extends ITianshuPayload> payloadClass) {
        String effectiveName = requireName(name);
        if (payloadClass == null) {
            throw new IllegalArgumentException("payload class must not be null for " + effectiveName);
        }
        Class<? extends ITianshuPayload> already = BY_CLASS_OF_NAME.putIfAbsent(effectiveName, payloadClass);
        if (already != null && already != payloadClass) {
            throw new IllegalStateException("payload type " + effectiveName + " is already bound to "
                    + already.getName() + " and cannot also be " + payloadClass.getName());
        }
        return register(effectiveName);
    }

    /** The payload class bound to {@code name}, or {@code null} when the name carries no binding. */
    public static Class<? extends ITianshuPayload> payloadClassOf(String name) {
        return name == null ? null : BY_CLASS_OF_NAME.get(name.trim());
    }

    public static boolean isRegistered(String name) {
        return name != null && BY_NAME.containsKey(name.trim());
    }

    /** Every registered kind, ordered by name; used by diagnostics and tests. */
    public static Collection<PayloadType> values() {
        return BY_NAME.values().stream()
                .sorted((left, right) -> left.name.compareTo(right.name))
                .toList();
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PayloadType that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("payload type name must not be blank");
        }
        return name.trim();
    }

    private static PayloadType register(String name) {
        return BY_NAME.computeIfAbsent(name, PayloadType::new);
    }
}
