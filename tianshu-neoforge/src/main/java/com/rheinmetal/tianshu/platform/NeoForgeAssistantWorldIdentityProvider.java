package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.function.assistant.scope.AssistantScopeKind;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScopeSnapshot;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantWorldIdentityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

public final class NeoForgeAssistantWorldIdentityProvider implements AssistantWorldIdentityProvider {
    @Override
    public AssistantScopeSnapshot currentWorldIdentity() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return AssistantScopeSnapshot.unknown();
        }
        String dimension = minecraft.level.dimension().location().toString();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            String address = server.ip == null ? "unknown" : server.ip.trim().toLowerCase();
            String name = server.name == null || server.name.isBlank() ? address : server.name.trim();
            return new AssistantScopeSnapshot(AssistantScopeKind.SERVER_WORLD, "server|" + address, name, dimension, true);
        }
        String levelName = "local_world";
        try {
            if (minecraft.getSingleplayerServer() != null) {
                levelName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception ignored) {
        }
        String stableIdentity = "local|" + minecraft.gameDirectory.toPath().toAbsolutePath().normalize() + "|" + levelName;
        return new AssistantScopeSnapshot(AssistantScopeKind.LOCAL_WORLD, stableIdentity, levelName, dimension, true);
    }
}
