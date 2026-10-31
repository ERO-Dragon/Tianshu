package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.client.host.ClientScheduler;
import net.minecraft.client.Minecraft;

public final class MainThreadScheduler implements ClientScheduler {
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
