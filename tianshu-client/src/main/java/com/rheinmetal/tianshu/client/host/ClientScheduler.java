package com.rheinmetal.tianshu.client.host;

public interface ClientScheduler {
    void execute(Runnable task);

    boolean isOnMainThread();
}
