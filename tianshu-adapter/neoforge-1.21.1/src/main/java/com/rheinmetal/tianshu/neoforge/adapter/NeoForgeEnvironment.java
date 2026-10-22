package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Objects;

public class NeoForgeEnvironment implements IGameEnvironment {

    private final Path gameDirectory;
    private volatile DiagnosticSink diagnosticSink = DiagnosticSink.NOOP;
    private volatile LogSink logSink = LogSink.NOOP;

    public NeoForgeEnvironment(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
    }

    public void bindDiagnostics(DiagnosticSink diagnosticSink) {
        this.diagnosticSink = diagnosticSink == null ? DiagnosticSink.NOOP : diagnosticSink;
    }

    public void bindLogs(LogSink logSink) {
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
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
        return gameDirectory;
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public void openFolder(Path dir) {
        if (dir == null) {
            warn("NEOFORGE_OPEN_FOLDER_INVALID_PATH");
            return;
        }
        try {
            net.minecraft.Util.getPlatform().openFile(dir.toFile());
        } catch (Exception e) {
            logSink.error("neoforge.open_folder.failed path=" + dir, e);
        }
    }

    @Override
    public void info(String msg) {
        logSink.info(msg);
    }

    @Override
    public void warn(String msg) {
        logSink.warn(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        logSink.error(msg, t);
    }

    @Override
    public DiagnosticSink diagnostics() {
        return diagnosticSink;
    }
}
