package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Decides whether a module's diagnostic events are accepted at all.
 *
 * <p>There is no fixed list of module ids here any more. The set is supplied at call time and comes from what is
 * actually assembled in the session — a module becomes visible to diagnostics by being loaded, not by editing
 * the host. That is the same rule the rest of the platform follows (a module registers, the host iterates), and
 * it means a new module's log lines and progress updates appear without a host release.</p>
 *
 * <p>The debug switch stays: diagnostics are a developer aid, and a release build writes none of this unless the
 * player turns it on.</p>
 */
public final class ClientDiagnosticPolicy implements Predicate<String> {
    private final ClientDiagnosticsConfiguration config;
    private final Supplier<Set<String>> knownModules;

    public ClientDiagnosticPolicy(ClientDiagnosticsConfiguration config, Supplier<Set<String>> knownModules) {
        this.config = Objects.requireNonNull(config, "config");
        this.knownModules = knownModules == null ? Set::of : knownModules;
    }

    @Override
    public boolean test(String moduleId) {
        if (moduleId == null || moduleId.isBlank() || !config.isDebugEnabled()) {
            return false;
        }
        Set<String> known = knownModules.get();
        return known != null && known.contains(moduleId);
    }
}
