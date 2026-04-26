package com.rheinmetal.tianshu.server;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.ClientConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = "tianshu", bus = EventBusSubscriber.Bus.MOD)
public class ServerConfigReloader {

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (!event.getConfig().getModId().equals(Tianshu.MOD_ID)) return;

        if (event.getConfig().getType() == net.neoforged.fml.config.ModConfig.Type.SERVER) {
            net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isRunning()) {
                PermissionSyncController.syncToAll(server);
            }
        }

        if (event.getConfig().getType() == net.neoforged.fml.config.ModConfig.Type.CLIENT) {
            ClientConfig.syncToFeatureManager();
        }
    }
}
