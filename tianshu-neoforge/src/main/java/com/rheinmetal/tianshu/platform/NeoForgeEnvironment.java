package com.rheinmetal.tianshu.platform;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;

public class NeoForgeEnvironment implements IGameEnvironment {

    private static final Logger LOGGER = LogUtils.getLogger();

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
        return Minecraft.getInstance().isSameThread();
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
}
