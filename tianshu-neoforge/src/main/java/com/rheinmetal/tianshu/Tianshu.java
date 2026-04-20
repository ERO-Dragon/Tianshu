package com.rheinmetal.tianshu;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.TianshuClient;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(Tianshu.MOD_ID)
public class Tianshu {
    public static final String MOD_ID = "tianshu";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tianshu(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("天枢模组开始加载...");

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, NeoForgeConfig.SPEC);
        modEventBus.addListener(TianshuClient::registerKeyMappings);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(TianshuClient::registerOverlays);
    }

    private void commonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
                TianshuClient.init();
            }
        });
    }
}
