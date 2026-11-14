package com.rheinmetal.tianshu.client.diagnostics;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides whether a module's diagnostic events are accepted at all.
 *
 * <p>There is no fixed list of module ids here. The set is supplied at call time and comes from what is actually
 * assembled in the session — a module becomes visible to diagnostics by being loaded, not by editing the host. That
 * is the same rule the rest of the platform follows (a module registers, the host iterates), and it means a new
 * module's log lines and progress updates appear without a host release.</p>
 *
 * <p>Admission says nothing about where a record ends up: that is {@link ClientDiagnosticRouter}'s call, and it is
 * where the debug switch lives (log always, in-game lines only while debugging).</p>
 */
public final class ClientDiagnosticPolicy implements Predicate<String> {
    private final Supplier<Set<String>> knownModules;

    public ClientDiagnosticPolicy(Supplier<Set<String>> knownModules) {
        this.knownModules = knownModules == null ? Set::of : knownModules;
    }

    @Override
    public boolean test(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return false;
        }
        Set<String> known = knownModules.get();
        return known != null && known.contains(moduleId);
    }
}

