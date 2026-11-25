package com.rheinmetal.tianshu.api.capability;

import java.util.Set;

/**
 * What one consumer accepts: from where, and of which kind.
 *
 * <p>Three independent questions, deliberately kept apart from the provider's own declaration:</p>
 * <ul>
 *   <li><strong>from where</strong> 鈥?by default, anything a provider <em>chose to open</em> ({@link #DEFAULT}), or an
 *   explicit list of providers, or everyone;</li>
 *   <li><strong>which kind</strong> 鈥?atomic capabilities and compositions are subscribed to separately, because a
 *   composition is someone else's sequencing of actions and should not appear on a tool list just because the module
 *   publishing it happens to be installed;</li>
 *   <li><strong>opened only</strong> 鈥?the default only accepts rows whose provider opened them, so a provider that
 *   declares nothing stays invisible without anyone having to name it here.</li>
 * </ul>
 *
 * <p>The default is narrow on purpose: what is open, atomically. A consumer that wants more 鈥?another module's private
 * capabilities, or compositions 鈥?says so.</p>
 */
public record CapabilitySubscription(boolean allProviders, Set<String> providers, boolean atomic, boolean composite,
                                     boolean openedOnly) {

    /**
     * Atomic capabilities whose provider opened them 鈥?what an assistant gets without declaring anything.
     *
     * <p>"Opened" rather than "the base": a provider has to say what it offers, and this side has to accept it. Naming
     * providers here instead would mean maintaining a list of who counts as local, which is a list that goes stale the
     * first time a new built-in provider appears.</p>
     */
    public static final CapabilitySubscription DEFAULT = new CapabilitySubscription(false, Set.of(), true, false, true);

    public CapabilitySubscription {
        providers = providers == null ? Set.of() : Set.copyOf(providers);
    }

    /** Accepts atomic capabilities from these providers, no compositions. */
    public static CapabilitySubscription atomicFrom(String... providers) {
        return new CapabilitySubscription(false, new java.util.LinkedHashSet<>(java.util.List.of(providers)), true,
                false, false);
    }

    /** Accepts both kinds from these providers. */
    public static CapabilitySubscription everythingFrom(String... providers) {
        return new CapabilitySubscription(false, new java.util.LinkedHashSet<>(java.util.List.of(providers)), true,
                true, false);
    }

    /** Accepts whatever any provider opened, compositions included. */
    public static CapabilitySubscription openedIncludingCompositions() {
        return new CapabilitySubscription(false, Set.of(), true, true, true);
    }

    /** Whether this consumer accepts that row at all (its provider's sharing is checked separately). */
    public boolean accepts(CapabilityDescriptor descriptor) {
        if (openedOnly) {
            if (CapabilitySharing.of(descriptor).scope() == CapabilitySharing.Scope.NONE) {
                return false;
            }
            return kindAccepted(descriptor.kind());
        }
        return accepts(descriptor.provider(), descriptor.kind());
    }

    /** The same rule for a provider and kind known before a row exists; an "opened only" rule cannot answer this. */
    public boolean accepts(String provider, CapabilityKind kind) {
        if (openedOnly) {
            return false;
        }
        if (!allProviders && !providers.contains(provider)) {
            return false;
        }
        return kindAccepted(kind);
    }

    private boolean kindAccepted(CapabilityKind kind) {
        return CapabilityKind.WORKFLOW.equals(kind) ? composite : atomic;
    }
}