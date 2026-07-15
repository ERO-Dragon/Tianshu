package com.rheinmetal.tianshu.neoforge;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.neoforge.bootstrap.NeoForgeClientBootstrap;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(TianshuNeoForge.MOD_ID)
public final class TianshuNeoForge {
    public static final String MOD_ID = "tianshu";
    public static final Logger LOGGER = LogUtils.getLogger();
    private final NeoForgeClientBootstrap clientBootstrap = new NeoForgeClientBootstrap();

    public TianshuNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("天枢模组开始加载...");

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modEventBus.addListener(clientBootstrap::registerKeyMappings);
        modEventBus.addListener(clientBootstrap::registerReloadListeners);
        modEventBus.addListener(this::clientSetup);
    }

    private void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(clientBootstrap::start);
    }
}
