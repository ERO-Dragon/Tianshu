package com.rheinmetal.tianshu.neoforge;

import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@Mod(TianshuNeoForge.MOD_ID)
public final class TianshuNeoForge {
    public static final String MOD_ID = "tianshu";
    private final NeoForgeClientBootstrap clientBootstrap = new NeoForgeClientBootstrap();

    public TianshuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modEventBus.addListener(clientBootstrap::registerKeyMappings);
        modEventBus.addListener(clientBootstrap::registerReloadListeners);
        modEventBus.addListener(clientBootstrap::registerShaders);
        modEventBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::gameShuttingDown);
    }

    private void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(clientBootstrap::start);
    }

    private void gameShuttingDown(GameShuttingDownEvent event) {
        clientBootstrap.shutdown();
    }
}
