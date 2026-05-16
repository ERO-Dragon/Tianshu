package com.rheinmetal.tianshu.function.assistant.scope;

import com.rheinmetal.tianshu.core.scope.WorldIdentityProvider;
import com.rheinmetal.tianshu.core.scope.WorldIdentitySnapshot;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;

public final class AssistantWorldIdentityAdapter implements AssistantWorldIdentityProvider {
    private final WorldIdentityProvider delegate;

    public AssistantWorldIdentityAdapter(WorldIdentityProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AssistantScopeSnapshot currentWorldIdentity() {
        WorldIdentitySnapshot snapshot = delegate == null ? WorldIdentitySnapshot.unknown() : delegate.currentWorldIdentity();
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
