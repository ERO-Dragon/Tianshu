package com.rheinmetal.tianshu.api.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What each module declared it accepts from others.
 *
 * <p>A module that declares nothing gets {@link CapabilitySubscription#DEFAULT}: the base's atomic capabilities and
 * nothing else. That default is what keeps "install two mods" from meaning "each can drive the other".</p>
 *
 * <p>{@link #audit()} exists because a subscription that names a provider the provider never opened is an easy mistake
 * and a silent one: the consumer simply sees nothing and nobody learns why. The audit turns that into a sentence, and a
 * startup hook can log it.</p>
 */
public final class CapabilitySubscriptions {

    private static final Map<String, CapabilitySubscription> RULES = new LinkedHashMap<>();

    private CapabilitySubscriptions() {
    }

    /** Records what {@code moduleId} accepts; a later call replaces the earlier one. */
    public static synchronized void declare(String moduleId, CapabilitySubscription rule) {
        Objects.requireNonNull(moduleId, "moduleId");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("a subscription needs a module id");
        }
        RULES.put(moduleId.trim(), rule == null ? CapabilitySubscription.DEFAULT : rule);
    }

    /** The rule for a consumer; the default when it declared none. */
    public static synchronized CapabilitySubscription ruleFor(String moduleId) {
        return RULES.getOrDefault(moduleId, CapabilitySubscription.DEFAULT);
    }

    /** Whether a module declared anything at all — used to tell "chose the default" from "did not ask". */
    public static synchronized boolean declared(String moduleId) {
        return RULES.containsKey(moduleId);
    }

    /** Everything declared, for diagnostics. */
    public static synchronized Map<String, CapabilitySubscription> declared() {
        return Map.copyOf(RULES);
    }

    /** Drops every declaration; a restart of the composition root must not inherit the previous session's rules. */
    public static synchronized void clear() {
        RULES.clear();
    }

    /**
     * Sentences describing declarations that cannot work.
     *
     * <p>Two distinct failures, told apart on purpose: a provider that shares nothing at all, and a provider that has no
     * rows (usually "not installed") — the first is a mistake in the declaration, the second is usually a mistake in the
     * mod list, and the fix is different.</p>
     */
    public static synchronized List<String> audit() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, CapabilitySubscription> entry : RULES.entrySet()) {
            String consumer = entry.getKey();
            CapabilitySubscription rule = entry.getValue();
            if (rule.allProviders()) {
                continue;
            }
            for (String provider : rule.providers()) {
                if (provider.equals(consumer) || provider.equals(CapabilitySharing.BASE_PROVIDER)) {
                    continue;
                }
                List<CapabilityDescriptor> rows = CapabilityCatalog.list().stream()
                        .filter(descriptor -> provider.equals(descriptor.provider()))
                        .toList();
                if (rows.isEmpty()) {
                    problems.add(consumer + " subscribes to " + provider + ", but it has published nothing (not installed?)");
                } else if (rows.stream().noneMatch(descriptor -> CapabilitySharing.of(descriptor).permits(consumer))) {
                    problems.add(consumer + " subscribes to " + provider + ", but " + provider
                            + " shares nothing with it");
                }
            }
        }
        return List.copyOf(problems);
    }
}