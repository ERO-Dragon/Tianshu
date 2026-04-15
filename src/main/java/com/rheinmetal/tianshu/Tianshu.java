package com.rheinmetal.tianshu;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.TianshuClient;

import java.io.File;

import org.slf4j.Logger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import com.rheinmetal.tianshu.config.Config;

@Mod(Tianshu.MOD_ID)
public class Tianshu {
    public static final String MOD_ID = "tianshu";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loaded = false;

    public Tianshu(IEventBus modEventBus, ModContainer modContainer) {
        if (net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
            try {
                loadSherpaNative();
                if (loaded) {
                    LOGGER.info("✅ sherpa-onnx native 加载成功！");
                }
            } catch (Throwable e) {
                LOGGER.error("❌ sherpa-onnx native 加载失败", e);
                // ❗ 不要抛异常，否则模组直接挂
            }
        }
        LOGGER.info("天枢模组开始加载...");

        // 【关键1】: 注册配置为 CLIENT
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, Config.SPEC);

        // 【关键2】: 在 MOD 总线上监听按键注册事件，转交给 Client 类处理
        modEventBus.addListener(TianshuClient::registerKeyMappings);

        // 【关键3】: 通用设置中，安全地启动客户端逻辑
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(com.rheinmetal.tianshu.client.TianshuClient::registerOverlays);
    }
    
    private void loadSherpaNative() {
        loadSherpaNativeStatic();
    }
    private void commonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.loading.FMLLoader.getDist().isClient()) {
                TianshuClient.init();
            }
        });
    }

    public static void reloadNative() {
        loadSherpaNativeStatic();
    }

    private static void loadSherpaNativeStatic() {
        if (loaded) return;
        String osName = System.getProperty("os.name").toLowerCase();
        if (!osName.contains("win")) {
            LOGGER.warn("当前系统非 Windows，跳过 Sherpa Native 加载");
            return;
        }

        try {
            File nativesDir;
            String nativesPath = System.getProperty("java.library.path");
            if (nativesPath != null && !nativesPath.isEmpty()) {
                nativesDir = new File(nativesPath.split(File.pathSeparator)[0]);
                if (!nativesDir.exists()) nativesDir.mkdirs();
            } else {
                nativesDir = new File(System.getProperty("java.io.tmpdir"), "tianshu_sherpa_cache");
                nativesDir.mkdirs();
            }

            File onnxFile = new File(nativesDir, "onnxruntime.dll");
            File jniFile = new File(nativesDir, "sherpa-onnx-jni.dll");

            if (onnxFile.exists() && onnxFile.length() > 10000 && jniFile.exists() && jniFile.length() > 10000) {
                LOGGER.info("Native DLL 已存在于 natives 目录，跳过提取");
            } else {
                java.nio.file.Path cachedJar = com.rheinmetal.tianshu.core.EnvSetupManager.getCacheDir()
                    .resolve("sherpa-onnx-native-cache.jar");

                if (!java.nio.file.Files.exists(cachedJar) || java.nio.file.Files.size(cachedJar) < 10000) {
                    LOGGER.warn("缓存 JAR 不存在，Native DLL 无法加载。请先在游戏内完成环境配置。");
                    return;
                }

                LOGGER.info("从缓存 JAR 提取 DLL 到 natives 目录: {}", cachedJar);
                com.rheinmetal.tianshu.core.EnvSetupManager.extractDllsFromJarStatic(cachedJar.toFile(), nativesDir);
            }

            File loadFile = new File(nativesDir, "onnxruntime.dll");
            System.load(loadFile.getAbsolutePath());
            LOGGER.info("✅ 成功加载 DLL: onnxruntime.dll");

            System.setProperty("sherpa_onnx.native.path", nativesDir.getAbsolutePath());
            LOGGER.info("已设置 sherpa_onnx.native.path = {}", nativesDir.getAbsolutePath());

            loaded = true;
            LOGGER.info("✅ 全部 Native 库准备完成！(sherpa-onnx-jni.dll 将由 LibraryLoader 在 PLUGIN 层加载)");
        } catch (Exception e) {
            LOGGER.error("❌ Sherpa Native 加载失败", e);
        }
    }
}
