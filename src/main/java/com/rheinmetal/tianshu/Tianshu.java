package com.rheinmetal.tianshu;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.TianshuClient;
import com.rheinmetal.tianshu.core.NativeLibManager;

import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import com.rheinmetal.tianshu.config.Config;

@Mod(Tianshu.MOD_ID)
public class Tianshu {
    public static final String MOD_ID = "tianshu";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static boolean nativesReady = false;

    public Tianshu(IEventBus modEventBus, ModContainer modContainer) {
        if (net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
            try {
                NativeLibManager.extractAndLoadAll();
                nativesReady = NativeLibManager.isNativesLoaded();
                if (nativesReady) {
                    LOGGER.info("Native 库提取与加载完成");
                } else {
                    LOGGER.warn("Native 库未完全加载，可能需要稍后在 GUI 中手动检测");
                }
            } catch (Throwable e) {
                LOGGER.error("Native 库提取/加载失败", e);
            }
        }
        LOGGER.info("天枢模组开始加载...");

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);
        modEventBus.addListener(TianshuClient::registerKeyMappings);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(com.rheinmetal.tianshu.client.TianshuClient::registerOverlays);
    }

    private void commonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
                TianshuClient.init();
            }
        });
    }

    public static void reloadNative() {
        if (!nativesReady) {
            NativeLibManager.extractAndLoadAll();
            nativesReady = NativeLibManager.isNativesLoaded();
        }
    }

    public static boolean isNativesReady() {
        return nativesReady;
    }
}
