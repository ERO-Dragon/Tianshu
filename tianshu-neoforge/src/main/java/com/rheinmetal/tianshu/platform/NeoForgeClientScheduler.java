package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.client.host.ClientScheduler;
import net.minecraft.client.Minecraft;

public final class NeoForgeClientScheduler implements ClientScheduler {
    @Override
    public void execute(Runnable task) {
        if (task != null) {
            Minecraft.getInstance().execute(task);
        }
    }

    @Override
    public boolean isOnMainThread() {
        return Minecraft.getInstance().isSameThread();
    }
}
