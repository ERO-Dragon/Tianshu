package com.rheinmetal.tianshu.api.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one consumer accepts: from where, of which kind, and — optionally — exactly which capabilities.
 *
 * <p>Four independent questions, deliberately kept apart from the provider's own declaration:</p>
 * <ul>
 *   <li><strong>from where</strong> — by default, anything a provider <em>chose to open</em> ({@link #DEFAULT}), or an
 *   explicit list of providers, or everyone;</li>
 *   <li><strong>which kind</strong> — atomic capabilities and compositions are subscribed to separately, because a
 *   composition is someone else's sequencing of actions and should not appear on a tool list just because the module
 *   publishing it happens to be installed;</li>
 *   <li><strong>opened only</strong> — the default only accepts rows whose provider opened them, so a provider that
 *   declares nothing stays invisible without anyone having to name it here;</li>
 *   <li><strong>which ones</strong> — naming capabilities ({@link #only}) narrows the subscription to those rows, which
 *   is how a module asks for three specific tools instead of the base's whole table.</li>
 * </ul>
 *
 * <p>Naming only ever takes away: it cannot reach a row its provider kept private, because the two halves of the gate
 * stay two halves. Nor does it hide the prose — a skill's text follows its provider, since knowing how to call the tools
 * you were given is the point of having them.</p>
 *
 * <p>The default is narrow on purpose: what is open, atomically. A consumer that wants more — another module's private
 * capabilities, or compositions — says so.</p>
 */
public record CapabilitySubscription(boolean allProviders, Set<String> providers, Set<CapabilityId> ids, boolean atomic,
                                     boolean composite, boolean openedOnly) {

    /**
     * Atomic capabilities whose provider opened them — what an assistant gets without declaring anything.
     *
     * <p>"Opened" rather than "the base": a provider has to say what it offers, and this side has to accept it. Naming
     * providers here instead would mean maintaining a list of who counts as local, which is a list that goes stale the
     * first time a new built-in provider appears.</p>
     */
    public static final CapabilitySubscription DEFAULT =
            new CapabilitySubscription(false, Set.of(), Set.of(), true, false, true);

    public CapabilitySubscription {
        providers = providers == null ? Set.of() : Set.copyOf(providers);
        ids = ids == null ? Set.of() : Set.copyOf(ids);
    }

    /** Accepts atomic capabilities from these providers, no compositions. */
    public static CapabilitySubscription atomicFrom(String... providers) {
        return new CapabilitySubscription(false, new LinkedHashSet<>(List.of(providers)), Set.of(), true, false, false);
    }

    /** Accepts both kinds from these providers. */
    public static CapabilitySubscription everythingFrom(String... providers) {
        return new CapabilitySubscription(false, new LinkedHashSet<>(List.of(providers)), Set.of(), true, true, false);
    }

    /** Accepts whatever any provider opened, compositions included. */
    public static CapabilitySubscription openedIncludingCompositions() {
        return new CapabilitySubscription(false, Set.of(), Set.of(), true, true, true);
    }

    /**
     * Accepts exactly these capabilities, as long as their providers opened them.
     *
     * <p>What a module writes when it wants three named tools rather than the base's whole table. A tool's document is an
     * attachment of its row, so narrowing the row takes the document with it — and a consumer that named a tool it cannot
     * see still gets nothing, because naming is not a grant.</p>
     */
    public static CapabilitySubscription only(CapabilityId... ids) {
        return new CapabilitySubscription(false, Set.of(), new LinkedHashSet<>(List.of(ids)), true, false, true);
    }

    /** Whether this consumer accepts that row at all (its provider's sharing is checked separately). */
    public boolean accepts(CapabilityDescriptor descriptor) {
        if (!namingAllows(descriptor)) {
            return false;
        }
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
        if (callable(kind) && !ids.isEmpty()) {
            // A named list cannot be answered from a provider and a kind: the caller would have to say which row.
            return false;
        }
        if (!allProviders && !providers.contains(provider)) {
            return false;
        }
        return kindAccepted(kind);
    }

    /**
     * Whether a named list lets this row through.
     *
     * <p>Only the callable rows are filtered — a tool or a composition is something a caller names and drives, while a
     * skill, resource or prompt is how a model learns to drive them. A consumer that narrowed to three tools still reads
     * the manual it needs; that its neighbour's manual is visible too is not a capability leak, because the neighbour's
     * tools are not.</p>
     */
    private boolean namingAllows(CapabilityDescriptor descriptor) {
        if (ids.isEmpty() || !callable(descriptor.kind())) {
            return true;
        }
        return ids.contains(descriptor.id());
    }

    private static boolean callable(CapabilityKind kind) {
        return CapabilityKind.TOOL.equals(kind) || CapabilityKind.WORKFLOW.equals(kind);
    }

    private boolean kindAccepted(CapabilityKind kind) {
        return CapabilityKind.WORKFLOW.equals(kind) ? composite : atomic;
    }
}