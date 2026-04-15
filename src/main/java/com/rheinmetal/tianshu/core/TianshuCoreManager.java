package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;
import com.rheinmetal.tianshu.core.engine.AsrEngine;
import com.rheinmetal.tianshu.utils.PathUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.io.File;

public class TianshuCoreManager {
    private static TianshuCoreManager instance;
    private boolean isEngineReady = false;
    private AsrEngine asrEngine;

    private TianshuCoreManager() {
    }

    public static synchronized TianshuCoreManager getInstance() {
        if (instance == null) {
            instance = new TianshuCoreManager();
        }
        return instance;
    }

    public boolean isEngineReady() {
        return isEngineReady;
    }

    private void tryInitEngine() {
        if (isEngineReady) {
            Tianshu.LOGGER.info("引擎已就绪，跳过初始化");
            return;
        }

        // 检查环境是否就绪
        if (!com.rheinmetal.tianshu.core.EnvSetupManager.isEnvironmentReady()) {
            Tianshu.LOGGER.info("环境未就绪，静默等待");
            return;
        }

        try {
            // 读取配置获取模型原路径
            String originalModelPath = Config.getAsrModelPath().toString();
            File originalDir = new File(originalModelPath);

            // 检查目录是否存在，且内部是否存在 tokens.txt
            if (!originalDir.exists() || !originalDir.isDirectory()) {
                Tianshu.LOGGER.info("模型目录不存在，静默等待");
                return;
            }

            File tokensFile = new File(originalDir, "tokens.txt");
            if (!tokensFile.exists() || !tokensFile.isFile()) {
                Tianshu.LOGGER.info("模型文件不完整，静默等待");
                return;
            }

            // 调用 PathUtils.getSafeModelDir() 获取安全路径
            File safeDir = PathUtils.getSafeModelDir(originalDir);
            if (safeDir == null) {
                Tianshu.LOGGER.error("获取安全模型目录失败");
                return;
            }

            // 实例化 AsrEngine 并调用 initialize()
            asrEngine = new AsrEngine();
            asrEngine.initialize(safeDir.getAbsolutePath());

            // 成功后将 isEngineReady 设为 true
            isEngineReady = true;
            Tianshu.LOGGER.info("✅ 引擎初始化成功");
            // 【新增】发出 ASR 就绪信号
            com.rheinmetal.tianshu.client.TianshuClient.asrReady = true;
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (net.minecraft.client.Minecraft.getInstance().player != null) {
                    net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§a[天枢] §f态势感知已就绪"), false
                    );
                }
            });
        } catch (Throwable t) {
            Tianshu.LOGGER.error("引擎初始化失败", t);
        }
    }

   // 暴露给外部调用的销毁方法
   public void destroyOnWorldLeft() {
       Tianshu.LOGGER.info("核心管理器：销毁引擎资源");
       if (asrEngine != null) {
           asrEngine.shutdown();
           asrEngine = null;
       }
       isEngineReady = false;
   }
   
   // 暴露给外部调用的初始化方法
   public void tryInitOnWorldJoined() {
       Tianshu.LOGGER.info("核心管理器：尝试初始化引擎");
       tryInitEngine();
   }

    public void onModelDownloadFinished() {
        Tianshu.LOGGER.info("模型下载完成，尝试初始化引擎");
        tryInitEngine();
    }

    public void onEnvSetupFinished() {
        Tianshu.LOGGER.info("环境配置完成，尝试初始化引擎");
        tryInitEngine();
    }

    public AsrEngine getAsrEngine() {
        return asrEngine;
    }
}