package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

public final class DefaultAXScopeProvider implements AXScopeProvider {
    private final WorldScopeProvider worldScopeProvider;

    public DefaultAXScopeProvider(IGameEnvironment env) {
        this(new DefaultWorldScopeProvider(new DefaultWorldIdentityProvider(env)));
    }

    public DefaultAXScopeProvider(AXWorldIdentityProvider worldIdentityProvider) {
        this(new DefaultWorldScopeProvider(() -> toWorldSnapshot(worldIdentityProvider == null ? AXScopeSnapshot.unknown() : worldIdentityProvider.currentWorldIdentity())));
    }

    public DefaultAXScopeProvider(WorldScopeProvider worldScopeProvider) {
        this.worldScopeProvider = worldScopeProvider;
    }

    @Override
    public AXScope currentScope() {
        WorldScope scope = worldScopeProvider == null ? WorldScope.unknown() : worldScopeProvider.currentScope();
        return new AXScope(scope.sharedUserId(), scope.worldId(), scope.displayName(), toAXKind(scope.kind()), scope.writable());
    }

    private static WorldIdentitySnapshot toWorldSnapshot(AXScopeSnapshot snapshot) {
        return new WorldIdentitySnapshot(toWorldKind(snapshot.kind()), snapshot.stableIdentity(), snapshot.displayName(), snapshot.dimensionId(), snapshot.writable());
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

    private static AXScopeKind toAXKind(WorldScopeKind kind) {
        return switch (kind == null ? WorldScopeKind.UNKNOWN : kind) {
            case SHARED -> AXScopeKind.SHARED;
            case LOCAL_WORLD -> AXScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> AXScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> AXScopeKind.REALMS_WORLD;
            default -> AXScopeKind.UNKNOWN;
        };
    }
}
