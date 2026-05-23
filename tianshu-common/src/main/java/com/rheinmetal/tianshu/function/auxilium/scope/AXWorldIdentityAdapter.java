package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;

public final class AXWorldIdentityAdapter implements AXWorldIdentityProvider {
    private final WorldIdentityProvider delegate;

    public AXWorldIdentityAdapter(WorldIdentityProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AXScopeSnapshot currentWorldIdentity() {
        WorldIdentitySnapshot snapshot = delegate == null ? WorldIdentitySnapshot.unknown() : delegate.currentWorldIdentity();
        return new AXScopeSnapshot(toAXKind(snapshot.kind()), snapshot.stableIdentity(), snapshot.displayName(), snapshot.dimensionId(), snapshot.writable());
    }

    private AXScopeKind toAXKind(WorldScopeKind kind) {
        return switch (kind == null ? WorldScopeKind.UNKNOWN : kind) {
            case SHARED -> AXScopeKind.SHARED;
            case LOCAL_WORLD -> AXScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> AXScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> AXScopeKind.REALMS_WORLD;
            default -> AXScopeKind.UNKNOWN;
        };
    }
}
