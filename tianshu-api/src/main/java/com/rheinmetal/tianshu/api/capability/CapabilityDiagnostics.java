package com.rheinmetal.tianshu.api.capability;

import java.util.function.Consumer;

/**
 * The declarations that cannot work, reported once the modules have had their say.
 *
 * <p>One entry point because they are the same question asked twice: "did everything the modules declared actually
 * connect?" A subscription to a provider that shares nothing and a capability documented by two manuals are both
 * declarations that silently do less than their author meant.</p>
 *
 * <p>Called from a "server started" hook rather than from registration, because modules publish while they load —
 * auditing earlier would report every provider as not installed.</p>
 */
public final class CapabilityDiagnostics {

    private CapabilityDiagnostics() {
    }

    /** Hands every problem to {@code warn}; a session with nothing wrong says nothing at all. */
    public static void report(Consumer<String> warn) {
        if (warn == null) {
            return;
        }
        for (String problem : CapabilitySubscriptions.audit()) {
            warn.accept(problem);
        }
        java.util.List<CapabilityDescriptor> rows = CapabilityCatalog.snapshot().descriptors();
        for (String problem : SkillCoverage.conflicts(rows)) {
            warn.accept(problem);
        }
        for (String problem : SkillCoverage.unknown(rows)) {
            warn.accept(problem);
        }
    }
}