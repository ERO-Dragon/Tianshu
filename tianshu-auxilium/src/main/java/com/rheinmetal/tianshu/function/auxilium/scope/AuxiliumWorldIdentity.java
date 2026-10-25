package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.api.game.WorldFacts;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Auxilium's reading of the platform's world facts.
 *
 * <p>The platform reports what it knows and names it in its own vocabulary; this class decides what
 * kind of scope that is. That keeps the classification — which is auxilium's opinion — out of the kernel
 * and out of the adapter.</p>
 */
public final class AuxiliumWorldIdentity implements AXWorldIdentityProvider {

    private final Supplier<WorldFacts> facts;

    public AuxiliumWorldIdentity(Supplier<WorldFacts> facts) {
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    @Override
    public AXScopeSnapshot currentWorldIdentity() {
        WorldFacts current = facts.get();
        if (current == null || !current.known()) {
            return AXScopeSnapshot.unknown();
        }
        return new AXScopeSnapshot(
                kindOf(current.kindId()),
                current.stableIdentity(),
                current.displayName().isBlank() ? current.dimensionId() : current.displayName(),
                current.dimensionId(),
                current.writable());
    }

    private static AXScopeKind kindOf(String kindId) {
        return switch (kindId) {
            case WorldFacts.KIND_SERVER_WORLD -> AXScopeKind.SERVER_WORLD;
            case WorldFacts.KIND_LOCAL_WORLD -> AXScopeKind.LOCAL_WORLD;
            case WorldFacts.KIND_REALMS_WORLD -> AXScopeKind.REALMS_WORLD;
            case WorldFacts.KIND_SHARED -> AXScopeKind.SHARED;
            default -> AXScopeKind.UNKNOWN;
        };
    }
}
