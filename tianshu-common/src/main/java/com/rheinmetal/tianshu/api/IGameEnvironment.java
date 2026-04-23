package com.rheinmetal.tianshu.api;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface IGameEnvironment {

    void displayMessageToPlayer(String message);

    void executeOnMainThread(Runnable task);

    Path getGameDirectory();

    boolean isClientSide();

    void openFolder(Path dir);

    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);
}
