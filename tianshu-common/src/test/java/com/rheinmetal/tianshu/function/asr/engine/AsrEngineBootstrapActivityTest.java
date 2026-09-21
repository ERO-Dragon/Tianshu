package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState;
import com.rheinmetal.tianshu.function.asr.AsrRuntimeCapabilities;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsrEngineBootstrapActivityTest {
    private static final String MODEL_KEY = "sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17";

    @TempDir
    Path tempDir;

    @Test
    void disabledAsrDoesNotEnterModelLoading() {
        List<Boolean> loading = new ArrayList<>();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        AsrEngineBootstrap bootstrap = new AsrEngineBootstrap(
                new FakeEnvironment(),
                new FakeConfiguration(false, tempDir, MODEL_KEY),
                ignored -> {},
                loading::add
        );

        bootstrap.initialize(context(runtimeState), "module.asr");

        assertEquals(List.of(), loading);
        assertEquals(RuntimeCapabilityState.DISABLED, runtimeState.capabilities().status(AsrRuntimeCapabilities.INPUT).state());
    }

    @Test
    void missingModelDirectoryDoesNotEnterModelLoading() {
        List<Boolean> loading = new ArrayList<>();
        AsrEngineBootstrap bootstrap = new AsrEngineBootstrap(
                new FakeEnvironment(),
                new FakeConfiguration(true, tempDir, MODEL_KEY),
                ignored -> {},
                loading::add
        );

        bootstrap.initialize(context(new ModuleRuntimeState()), "module.asr");

        assertEquals(List.of(), loading);
    }

    @Test
    void actualEngineInitializationBalancesLoadingEvenWhenModelFilesAreMissing() throws Exception {
        Files.createDirectories(tempDir.resolve("model").resolve(MODEL_KEY));
        List<Boolean> loading = new ArrayList<>();
        AsrEngineBootstrap bootstrap = new AsrEngineBootstrap(
                new FakeEnvironment(),
                new FakeConfiguration(true, tempDir, MODEL_KEY),
                ignored -> {},
                loading::add
        );

        bootstrap.initialize(context(new ModuleRuntimeState()), "module.asr");

        assertEquals(List.of(true, false), loading);
    }

    private static ModuleRuntimeContext context(ModuleRuntimeState runtimeState) {
        return new ModuleRuntimeContext(null, new ModuleServiceRegistry(), null, runtimeState);
    }

    private record FakeConfiguration(boolean enabled, Path basePath, String modelName) implements AsrConfiguration {
        @Override public boolean isAsrEnabled() { return enabled; }
        @Override public TriggerMode getTriggerMode() { return TriggerMode.ALWAYS; }
        @Override public String getSelectedMicName() { return ""; }
        @Override public boolean isAsrRnnoiseEnabled() { return false; }
        @Override public boolean isAsrHighPassFilterEnabled() { return true; }
        @Override public String getCustomAsrName() { return modelName; }
        @Override public Path getAsrBasePath() { return basePath; }
    }

    private static final class FakeEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public DiagnosticSink diagnostics() { return DiagnosticSink.NOOP; }
    }
}
