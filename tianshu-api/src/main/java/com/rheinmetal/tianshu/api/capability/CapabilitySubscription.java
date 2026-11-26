package com.rheinmetal.tianshu.api.capability;

import java.util.List;
import java.util.Set;

/** External rows selected by a consumer. Provider sharing is checked independently by CapabilityAccess. */
public record CapabilitySubscription(Set<CapabilityId> namedIds, Set<String> providers, boolean allProviders,
                                     boolean atomic, boolean composite) {

    public static final CapabilitySubscription DEFAULT = only();

    public CapabilitySubscription {
        namedIds = Set.copyOf(namedIds);
        providers = Set.copyOf(providers);
    }

    /** Selects individual rows of any kind. An empty selection accepts no external rows. */
    public static CapabilitySubscription only(CapabilityId... ids) {
        return new CapabilitySubscription(Set.copyOf(List.of(ids)), Set.of(), false, false, false);
    }

    /** Selects current and future atomic rows from the named providers. */
    public static CapabilitySubscription atomicFrom(String... providers) {
        return new CapabilitySubscription(Set.of(), Set.copyOf(List.of(providers)), false, true, false);
    }

    /** Selects current and future rows from the named providers, including workflows. */
    public static CapabilitySubscription everythingFrom(String... providers) {
        return new CapabilitySubscription(Set.of(), Set.copyOf(List.of(providers)), false, true, true);
    }

    public static CapabilitySubscription openedAtomic() {
        return new CapabilitySubscription(Set.of(), Set.of(), true, true, false);
    }

    public static CapabilitySubscription openedIncludingCompositions() {
        return new CapabilitySubscription(Set.of(), Set.of(), true, true, true);
    }

    public boolean accepts(CapabilityDescriptor row) {
        return namedIds.contains(row.id()) || (allProviders || providers.contains(row.provider()))
                && (CapabilityKind.WORKFLOW.equals(row.kind()) ? composite : atomic);
    }
}
