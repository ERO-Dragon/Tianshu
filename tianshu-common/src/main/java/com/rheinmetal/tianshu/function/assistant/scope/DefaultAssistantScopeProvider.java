package com.rheinmetal.tianshu.function.assistant.scope;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.DefaultWorldScopeProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.core.scope.WorldScopeProvider;

public final class DefaultAssistantScopeProvider implements AssistantScopeProvider {
    private final WorldScopeProvider worldScopeProvider;

    public DefaultAssistantScopeProvider(IGameEnvironment env) {
        this(new DefaultWorldScopeProvider(new DefaultWorldIdentityProvider(env)));
    }

    public DefaultAssistantScopeProvider(AssistantWorldIdentityProvider worldIdentityProvider) {
        this(new DefaultWorldScopeProvider(() -> toWorldSnapshot(worldIdentityProvider == null ? AssistantScopeSnapshot.unknown() : worldIdentityProvider.currentWorldIdentity())));
    }

    public DefaultAssistantScopeProvider(WorldScopeProvider worldScopeProvider) {
        this.worldScopeProvider = worldScopeProvider;
    }

    @Override
    public AssistantScope currentScope() {
        WorldScope scope = worldScopeProvider == null ? WorldScope.unknown() : worldScopeProvider.currentScope();
        return new AssistantScope(scope.sharedUserId(), scope.worldId(), scope.displayName(), toAssistantKind(scope.kind()), scope.writable());
    }

    private static WorldIdentitySnapshot toWorldSnapshot(AssistantScopeSnapshot snapshot) {
        return new WorldIdentitySnapshot(toWorldKind(snapshot.kind()), snapshot.stableIdentity(), snapshot.displayName(), snapshot.dimensionId(), snapshot.writable());
    }

    private static WorldScopeKind toWorldKind(AssistantScopeKind kind) {
        return switch (kind == null ? AssistantScopeKind.UNKNOWN : kind) {
            case SHARED -> WorldScopeKind.SHARED;
            case LOCAL_WORLD -> WorldScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> WorldScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> WorldScopeKind.REALMS_WORLD;
            default -> WorldScopeKind.UNKNOWN;
        };
    }

    private static AssistantScopeKind toAssistantKind(WorldScopeKind kind) {
        return switch (kind == null ? WorldScopeKind.UNKNOWN : kind) {
            case SHARED -> AssistantScopeKind.SHARED;
            case LOCAL_WORLD -> AssistantScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> AssistantScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> AssistantScopeKind.REALMS_WORLD;
            default -> AssistantScopeKind.UNKNOWN;
        };
    }
}
