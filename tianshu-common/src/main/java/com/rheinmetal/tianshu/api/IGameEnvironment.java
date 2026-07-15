package com.rheinmetal.tianshu.api;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;

import java.nio.file.Path;

public interface IGameEnvironment {

    void displayMessageToPlayer(String message);

    void executeOnMainThread(Runnable task);

    Path getGameDirectory();

    boolean isClientSide();

    void openFolder(Path dir);

    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);

    DiagnosticSink diagnostics();
}
