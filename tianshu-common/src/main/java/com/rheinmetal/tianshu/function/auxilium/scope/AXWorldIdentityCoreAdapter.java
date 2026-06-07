package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;

public final class AXWorldIdentityCoreAdapter implements WorldIdentityProvider {
    private final AXWorldIdentityProvider delegate;

    public AXWorldIdentityCoreAdapter(AXWorldIdentityProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public WorldIdentitySnapshot currentWorldIdentity() {
        AXScopeSnapshot snapshot = delegate == null ? AXScopeSnapshot.unknown() : delegate.currentWorldIdentity();
        return new WorldIdentitySnapshot(
                toWorldKind(snapshot.kind()),
                snapshot.stableIdentity(),
                snapshot.displayName(),
                snapshot.dimensionId(),
                snapshot.writable()
        );
    }

    private static WorldScopeKind toWorldKind(AXScopeKind kind) {
        return switch (kind == null ? AXScopeKind.UNKNOWN : kind) {
            case SHARED -> WorldScopeKind.SHARED;
            case LOCAL_WORLD -> WorldScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> WorldScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> WorldScopeKind.REALMS_WORLD;
            default -> WorldScopeKind.UNKNOWN;
        };
    }
}
