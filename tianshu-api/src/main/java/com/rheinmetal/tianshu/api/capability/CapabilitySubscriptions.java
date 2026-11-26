package com.rheinmetal.tianshu.api.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What each module declared it accepts from others.
 *
 * <p>A module that declares nothing gets {@link CapabilitySubscription#DEFAULT}: no external capabilities. That default is what keeps "install two mods" from meaning "each can drive the other".</p>
 *
 * <p>{@link #audit()} exists because a subscription that names a provider the provider never opened — or a capability
 * nobody publishes — is an easy mistake and a silent one: the consumer simply sees nothing and nobody learns why. The
 * audit turns that into a sentence, and a startup hook can log it.</p>
 */
public final class CapabilitySubscriptions {

    private static final Map<String, CapabilitySubscription> RULES = new LinkedHashMap<>();

    private static long revision;
    private static final java.util.concurrent.CopyOnWriteArrayList<Observer> OBSERVERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private record Observer(String module, java.util.function.BiConsumer<CapabilitySubscription, CapabilitySubscription> listener) {}

    static Runnable observe(String module, java.util.function.BiConsumer<CapabilitySubscription, CapabilitySubscription> listener) {
        Observer observer = new Observer(module, listener);
        OBSERVERS.add(observer);
        return () -> OBSERVERS.remove(observer);
    }

    static synchronized long revision() {
        return revision;
    }

    private CapabilitySubscriptions() {
    }

    /** Records what {@code moduleId} accepts; a later call replaces the earlier one. */
    public static void declare(String moduleId, CapabilitySubscription rule) {
        Objects.requireNonNull(moduleId, "moduleId");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("a subscription needs a module id");
        }
        replace(moduleId.trim(), rule == null ? CapabilitySubscription.DEFAULT : rule);
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

    public static void withdraw(String moduleId) {
        replace(moduleId, null);
    }

    /** Drops every declaration; a restart of the composition root must not inherit the previous session's rules. */
    public static void clear() {
        declared().keySet().forEach(CapabilitySubscriptions::withdraw);
    }

    private static void replace(String module, CapabilitySubscription rule) {
        CapabilitySubscription before;
        CapabilitySubscription after;
        synchronized (CapabilitySubscriptions.class) {
            before = ruleFor(module);
            if (rule == null) {
                RULES.remove(module);
            } else {
                RULES.put(module, rule);
            }
            after = ruleFor(module);
            if (before.equals(after)) {
                return;
            }
            revision++;
        }
        for (Observer observer : OBSERVERS) {
            if (observer.module().equals(module)) {
                try {
                    observer.listener().accept(before, after);
                } catch (RuntimeException ignored) {
                    // One session listener must not interrupt other sessions' invalidation.
                }
            }
        }
    }

    /**
     * Sentences describing declarations that cannot work.
     *
     * <p>Three distinct failures, told apart on purpose: a provider that shares nothing at all, a provider that has no
     * rows (usually "not installed"), and a named capability that nobody publishes (usually a typo) — the first is a
     * mistake in the declaration, the second is usually a mistake in the mod list, and the third is usually a mistake in
     * the id, so the fixes differ.</p>
     */
    public static synchronized List<String> audit() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, CapabilitySubscription> entry : RULES.entrySet()) {
            String consumer = entry.getKey();
            CapabilitySubscription rule = entry.getValue();
            for (CapabilityId id : rule.namedIds()) {
                if (CapabilityCatalog.find(id).isEmpty()) {
                    problems.add(consumer + " subscribes to " + id
                            + ", but nothing publishes it (not installed or misspelled)");
                }
            }
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