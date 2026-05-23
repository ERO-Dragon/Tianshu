package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.api.IGameEnvironment;

public final class DefaultAXWorldIdentityProvider implements AXWorldIdentityProvider {
    private final DefaultWorldIdentityProvider delegate;

    public DefaultAXWorldIdentityProvider(IGameEnvironment env) {
        this.delegate = new DefaultWorldIdentityProvider(env);
    }

    @Override
    public AXScopeSnapshot currentWorldIdentity() {
        WorldIdentitySnapshot snapshot = delegate.currentWorldIdentity();
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
