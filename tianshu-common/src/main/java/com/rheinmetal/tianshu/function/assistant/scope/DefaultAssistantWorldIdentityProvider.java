package com.rheinmetal.tianshu.function.assistant.scope;

import com.rheinmetal.tianshu.core.scope.DefaultWorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.api.IGameEnvironment;

public final class DefaultAssistantWorldIdentityProvider implements AssistantWorldIdentityProvider {
    private final DefaultWorldIdentityProvider delegate;

    public DefaultAssistantWorldIdentityProvider(IGameEnvironment env) {
        this.delegate = new DefaultWorldIdentityProvider(env);
    }

    @Override
    public AssistantScopeSnapshot currentWorldIdentity() {
        WorldIdentitySnapshot snapshot = delegate.currentWorldIdentity();
        return new AssistantScopeSnapshot(toAssistantKind(snapshot.kind()), snapshot.stableIdentity(), snapshot.displayName(), snapshot.dimensionId(), snapshot.writable());
    }

    private AssistantScopeKind toAssistantKind(WorldScopeKind kind) {
        return switch (kind == null ? WorldScopeKind.UNKNOWN : kind) {
            case SHARED -> AssistantScopeKind.SHARED;
            case LOCAL_WORLD -> AssistantScopeKind.LOCAL_WORLD;
            case SERVER_WORLD -> AssistantScopeKind.SERVER_WORLD;
            case REALMS_WORLD -> AssistantScopeKind.REALMS_WORLD;
            default -> AssistantScopeKind.UNKNOWN;
        };
    }
}
