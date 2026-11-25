package com.rheinmetal.tianshu.api.capability;

import java.util.Set;

/**
 * What one consumer accepts: from which providers, and of which kind.
 *
 * <p>Two independent questions, deliberately kept apart from the provider's declaration:</p>
 * <ul>
 *   <li><strong>from whom</strong> — a module names the providers it takes capabilities from;</li>
 *   <li><strong>which kind</strong> — atomic capabilities and composite ones are subscribed to separately, because a
 *   composition is someone else's sequencing of actions, and it should not appear on a model's tool list just because
 *   the module that publishes it happens to be installed.</li>
 * </ul>
 *
 * <p>The default is narrow on purpose: the base's atomic capabilities, nothing from other modules, no compositions. A
 * consumer that wants more says so.</p>
 */
public record CapabilitySubscription(boolean allProviders, Set<String> providers, boolean atomic, boolean composite) {

    /** Base atomic capabilities only — what an assistant gets without declaring anything. */
    public static final CapabilitySubscription DEFAULT =
            new CapabilitySubscription(false, CapabilitySharing.OPEN_PROVIDERS, true, false);

    public CapabilitySubscription {
        providers = providers == null ? Set.of() : Set.copyOf(providers);
    }


    /** Accepts atomic capabilities from these providers, no compositions. */
    public static CapabilitySubscription atomicFrom(String... providers) {
        return new CapabilitySubscription(false, new java.util.LinkedHashSet<>(java.util.List.of(providers)), true, false);
    }

    /** Accepts both kinds from these providers. */
    public static CapabilitySubscription everythingFrom(String... providers) {
        return new CapabilitySubscription(false, new java.util.LinkedHashSet<>(java.util.List.of(providers)), true, true);
    }



    /** Whether this consumer accepts that row at all (its provider's sharing is checked separately). */
    public boolean accepts(CapabilityDescriptor descriptor) {
        return accepts(descriptor.provider(), descriptor.kind());
    }

    /** The same rule for a provider and kind that are known before a row exists. */
    public boolean accepts(String provider, CapabilityKind kind) {
        if (!allProviders && !providers.contains(provider)) {
            return false;
        }
        boolean isComposite = CapabilityKind.WORKFLOW.equals(kind);
        return isComposite ? composite : atomic;
    }
}