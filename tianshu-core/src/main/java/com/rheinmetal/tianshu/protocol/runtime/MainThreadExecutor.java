package com.rheinmetal.tianshu.protocol.runtime;

public interface MainThreadExecutor {
    void execute(Runnable runnable);
}
