package com.rheinmetal.tianshu.core.scope;

public final class DefaultWorldScopeProvider implements WorldScopeProvider {
    private final WorldIdentityProvider identityProvider;

    public DefaultWorldScopeProvider(WorldIdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @Override
    public WorldScope currentScope() {
        WorldIdentitySnapshot snapshot = identityProvider == null ? WorldIdentitySnapshot.unknown() : identityProvider.currentWorldIdentity();
        return WorldScopeFactory.fromSnapshot(snapshot);
    }
}
