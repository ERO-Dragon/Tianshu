package com.rheinmetal.tianshu.core.scope;

import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.nio.file.Path;

public final class DefaultWorldIdentityProvider implements WorldIdentityProvider {
    private final IGameEnvironment env;

    public DefaultWorldIdentityProvider(IGameEnvironment env) {
        this.env = env;
    }

    @Override
    public WorldIdentitySnapshot currentWorldIdentity() {
        if (env == null || env.getGameDirectory() == null) {
            return WorldIdentitySnapshot.unknown();
        }
        Path gameDirectory = env.getGameDirectory().toAbsolutePath().normalize();
        String displayName = gameDirectory.getFileName() == null ? "Local Game" : gameDirectory.getFileName().toString();
        return new WorldIdentitySnapshot(WorldScopeKind.LOCAL_WORLD, gameDirectory.toString(), displayName, "", true);
    }
}
