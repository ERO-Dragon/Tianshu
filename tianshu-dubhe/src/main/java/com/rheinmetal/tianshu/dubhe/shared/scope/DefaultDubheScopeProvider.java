package com.rheinmetal.tianshu.dubhe.shared.scope;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

public final class DefaultDubheScopeProvider implements DubheScopeProvider {
    private final WorldScopeProvider worldScopeProvider;

    public DefaultDubheScopeProvider(IGameEnvironment env) {
        this(new DefaultWorldScopeProvider(new DefaultWorldIdentityProvider(env)));
    }

    public DefaultDubheScopeProvider(DubheWorldIdentityProvider worldIdentityProvider) {
        this(new DefaultWorldScopeProvider(() -> toWorldSnapshot(worldIdentityProvider == null ? DubheScopeSnapshot.unknown() : worldIdentityProvider.currentWorldIdentity())));
    }

    public DefaultDubheScopeProvider(WorldScopeProvider worldScopeProvider) {
        this.worldScopeProvider = worldScopeProvider;
    }

    @Override
    public DubheScope currentScope() {
        WorldScope scope = worldScopeProvider == null ? WorldScope.unknown() : worldScopeProvider.currentScope();
        return new DubheScope(scope.sharedUserId(), scope.worldId(), scope.displayName(), toDubheKind(scope.kind()), scope.writable());
    }

    private static WorldIdentitySnapshot toWorldSnapshot(DubheScopeSnapshot snapshot) {
        return new WorldIdentitySnapshot(toWorldKind(snapshot.kind()), snapshot.stableIdentity(), snapshot.displayName(), snapshot.dimensionId(), snapshot.writable());
    }

    private static WorldScopeKind toWorldKind(DubheScopeKind kind) {
        return switch (kind == null ? DubheScopeKind.UNKNOWN : kind) {
            case SHARED -> WorldScopeKind.SHARED;
            case LOCAL_WORLD -> WorldScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> WorldScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> WorldScopeKind.REALMS_WORLD;
            default -> WorldScopeKind.UNKNOWN;
        };
    }

    private static DubheScopeKind toDubheKind(WorldScopeKind kind) {
        return switch (kind == null ? WorldScopeKind.UNKNOWN : kind) {
            case SHARED -> DubheScopeKind.SHARED;
            case LOCAL_WORLD -> DubheScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> DubheScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> DubheScopeKind.REALMS_WORLD;
            default -> DubheScopeKind.UNKNOWN;
        };
    }
}
