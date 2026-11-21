package com.rheinmetal.tianshu.dubhe.scope;

import com.rheinmetal.tianshu.api.game.WorldFacts;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Dubhe's reading of the platform's world facts.
 *
 * <p>The platform reports what it knows and names it in its own vocabulary; this class decides what
 * kind of scope that is. That keeps the classification — which is dubhe's opinion — out of the kernel
 * and out of the adapter.</p>
 */
public final class DubheWorldIdentity implements DubheWorldIdentityProvider {

    private final Supplier<WorldFacts> facts;

    public DubheWorldIdentity(Supplier<WorldFacts> facts) {
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    @Override
    public DubheScopeSnapshot currentWorldIdentity() {
        WorldFacts current = facts.get();
        if (current == null || !current.known()) {
            return DubheScopeSnapshot.unknown();
        }
        return new DubheScopeSnapshot(
                kindOf(current.kindId()),
                current.stableIdentity(),
                current.displayName().isBlank() ? current.dimensionId() : current.displayName(),
                current.dimensionId(),
                current.writable());
    }

    private static DubheScopeKind kindOf(String kindId) {
        return switch (kindId) {
            case WorldFacts.KIND_SERVER_WORLD -> DubheScopeKind.SERVER_WORLD;
            case WorldFacts.KIND_LOCAL_WORLD -> DubheScopeKind.LOCAL_WORLD;
            case WorldFacts.KIND_REALMS_WORLD -> DubheScopeKind.REALMS_WORLD;
            case WorldFacts.KIND_SHARED -> DubheScopeKind.SHARED;
            default -> DubheScopeKind.UNKNOWN;
        };
    }
}
