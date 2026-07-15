package com.rheinmetal.tianshu.neoforge.adapter;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;

public class NeoForgeEnvironment implements IGameEnvironment {

    private static final Logger LOGGER = LogUtils.getLogger();
    private volatile DiagnosticSink diagnosticSink = DiagnosticSink.NOOP;

    public void bindDiagnostics(DiagnosticSink diagnosticSink) {
        this.diagnosticSink = diagnosticSink == null ? DiagnosticSink.NOOP : diagnosticSink;
    }

    @Override
    public void displayMessageToPlayer(String message) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(message), false
                );
            }
        });
    }

    @Override
    public void executeOnMainThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public Path getGameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public void openFolder(Path dir) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            warn("无法打开目录，路径无效: " + dir);
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openFile(dir.toFile());
        } catch (Exception e) {
            LOGGER.error("打开目录失败: " + dir, e);
        }
    }

    @Override
    public void info(String msg) {
        LOGGER.info(msg);
    }

    @Override
    public void warn(String msg) {
        LOGGER.warn(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        if (t != null) {
            LOGGER.error(msg, t);
        } else {
            LOGGER.error(msg);
        }
    }

    @Override
    public DiagnosticSink diagnostics() {
        return diagnosticSink;
    }
}
