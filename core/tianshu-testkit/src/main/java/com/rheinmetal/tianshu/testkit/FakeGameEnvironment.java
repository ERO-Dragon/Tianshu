package com.rheinmetal.tianshu.testkit;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link IGameEnvironment} for tests in any module.
 *
 * <p>It lives in its own project because every module's tests need it while no module's production
 * code may depend on a test double. Depending only on {@code tianshu-api} keeps the testkit usable
 * from api, core and feature tests without creating a cycle.</p>
 */
public final class FakeGameEnvironment implements IGameEnvironment {

    public final List<String> infos = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    @Override
    public void executeOnMainThread(Runnable task) {
        task.run();
    }

    @Override
    public Path getGameDirectory() {
        return Path.of(".");
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public void openFolder(Path dir) {
    }

    @Override
    public void info(String msg) {
        infos.add(msg);
    }

    @Override
    public void warn(String msg) {
        warnings.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        errors.add(msg);
    }

    @Override
    public DiagnosticSink diagnostics() {
        return DiagnosticSink.NOOP;
    }
}
