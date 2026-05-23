package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeSnapshot;
import com.rheinmetal.tianshu.function.auxilium.scope.AXWorldIdentityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

public final class NeoForgeAXWorldIdentityProvider implements AXWorldIdentityProvider {
    @Override
    public AXScopeSnapshot currentWorldIdentity() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return AXScopeSnapshot.unknown();
        }
        String dimension = minecraft.level.dimension().location().toString();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            String address = server.ip == null ? "unknown" : server.ip.trim().toLowerCase();
            String name = server.name == null || server.name.isBlank() ? address : server.name.trim();
            return new AXScopeSnapshot(AXScopeKind.SERVER_WORLD, "server|" + address, name, dimension, true);
        }
        String levelName = "local_world";
        try {
            if (minecraft.getSingleplayerServer() != null) {
                levelName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception ignored) {
        }
        String stableIdentity = "local|" + minecraft.gameDirectory.toPath().toAbsolutePath().normalize() + "|" + levelName;
        return new AXScopeSnapshot(AXScopeKind.LOCAL_WORLD, stableIdentity, levelName, dimension, true);
    }
}
