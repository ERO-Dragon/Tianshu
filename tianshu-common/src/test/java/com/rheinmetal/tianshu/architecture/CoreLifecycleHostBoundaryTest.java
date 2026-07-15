package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLifecycleHostBoundaryTest {
    @Test
    void coreLifecycleQueueDoesNotBecomeASecondProtocolTaskScheduler() throws Exception {
        String queue = commonSource("com/rheinmetal/tianshu/core/runtime/CoreLifecycleCommandQueue.java");

        assertFalse(queue.contains("ProtocolRuntime"));
        assertFalse(queue.contains("ProtocolExecutorManager"));
        assertFalse(queue.contains("ExecutionLane"));
        assertFalse(queue.contains("ProtocolTask"));
        assertTrue(queue.contains("Tianshu-Core-Lifecycle"));
    }

    @Test
    void coreManagerExposesOneFutureBasedLifecycleApiWithoutLegacyCallbacks() throws Exception {
        String manager = commonSource("com/rheinmetal/tianshu/core/TianshuCoreManager.java");

        assertTrue(manager.contains("CompletableFuture<CoreRuntimeStatus> startRuntimeSession()"));
        assertTrue(manager.contains("CompletableFuture<CoreRuntimeStatus> stopRuntimeSession()"));
        assertTrue(manager.contains("CompletableFuture<CoreRuntimeStatus> refreshRuntime(RuntimeRefreshReason reason)"));
        assertTrue(manager.contains("CompletableFuture<CoreRuntimeStatus> destroy()"));
        assertFalse(manager.contains("initWorkers("));
        assertFalse(manager.contains("restartRuntimeAsync("));
        assertFalse(manager.contains("refreshRuntimeAsync("));
        assertFalse(manager.contains("onEnvSetupFinished("));
    }

    @Test
    void neoforgeWorldEventsOnlySubmitAsyncCoreLifecycleCommands() throws Exception {
        Path neoforgeRoot = Path.of("../tianshu-neoforge/src/main/java");
        String events = Files.readString(
                neoforgeRoot.resolve("com/rheinmetal/tianshu/neoforge/event/NeoForgeClientEvents.java"),
                StandardCharsets.UTF_8
        );
        String runtime = Files.readString(
                Path.of("../tianshu-client/src/main/java/com/rheinmetal/tianshu/client/runtime/TianshuClientRuntime.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(events.contains("lifecycleAdapter.onWorldLogin()"));
        assertTrue(events.contains("lifecycleAdapter.onWorldLogout()"));
        assertTrue(runtime.contains("core.start().whenComplete"));
        assertTrue(runtime.contains("core.stop().whenComplete"));
        assertFalse(events.contains("coreManager.initWorkers()"));
        assertFalse(events.contains("coreManager.restartRuntimeAsync("));
        assertFalse(events.contains("coreManager.refreshRuntimeAsync("));

        try (Stream<Path> files = Files.walk(neoforgeRoot)) {
            String legacyCalls = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsAny(
                            path,
                            "coreManager.initWorkers()",
                            "coreManager.restartRuntimeAsync(",
                            "coreManager.refreshRuntimeAsync("
                    ))
                    .map(neoforgeRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);

            assertTrue(legacyCalls.isBlank(), legacyCalls);
        }
    }

    @Test
    void onnxBootstrapIsARequiredLifecycleModuleInsteadOfLoginThreadWork() throws Exception {
        Path neoforgeRoot = Path.of("../tianshu-neoforge/src/main/java");
        String bootstrap = Files.readString(
                neoforgeRoot.resolve("com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );
        Path clientRoot = Path.of("../tianshu-client/src/main/java");
        Path modulePath = clientRoot.resolve(
                "com/rheinmetal/tianshu/client/runtime/module/ClientOnnxRuntimeModule.java"
        );
        Path installerPath = clientRoot.resolve(
                "com/rheinmetal/tianshu/client/runtime/module/ClientOnnxRuntimeModuleInstaller.java"
        );

        assertFalse(bootstrap.contains("ensureOnnxRuntimeLoaded()"));
        assertFalse(bootstrap.contains("OrtEnvironment.getEnvironment()"));
        assertTrue(Files.isRegularFile(modulePath));
        assertTrue(Files.isRegularFile(installerPath));

        String module = Files.readString(modulePath, StandardCharsets.UTF_8);
        String installer = Files.readString(installerPath, StandardCharsets.UTF_8);
        assertTrue(module.contains("implements TianshuManagedModule"));
        assertTrue(module.contains("OrtEnvironment.getEnvironment()"));
        assertFalse(module.contains("catch (Throwable"));
        assertTrue(installer.contains("registerRequiredModule"));
    }

    @Test
    void irNamedObjectIndexIsClientLifetimeAsyncResource() throws Exception {
        Path neoforgeRoot = Path.of("../tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/neoforge");
        Path platformClientRoot = Path.of("../tianshu-client/src/main/java/com/rheinmetal/tianshu/client");
        String bootstrap = Files.readString(
                neoforgeRoot.resolve("bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );
        String runtime = Files.readString(
                platformClientRoot.resolve("runtime/TianshuClientRuntime.java"),
                StandardCharsets.UTF_8
        );
        String manager = Files.readString(
                platformClientRoot.resolve("ir/ClientNamedObjectIndexManager.java"),
                StandardCharsets.UTF_8
        );
        String reloadListener = Files.readString(
                neoforgeRoot.resolve("event/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(runtime.contains("namedObjectIndexManager().initializeAsync("));
        assertTrue(runtime.contains("namedObjectIndexManager()::close"));
        assertFalse(bootstrap.contains("ClientNamedObjectIndexManager.ensureIndex(\"client login\")"));
        assertTrue(manager.contains("ExecutorService"));
        assertTrue(manager.contains("initializeAsync("));
        assertTrue(manager.contains("reloadAsync("));
        assertFalse(manager.contains("catch (Throwable throwable)"));
        assertTrue(reloadListener.contains("indexManager.reloadAsync("));
    }

    private static String commonSource(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java").resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static boolean containsAny(Path path, String... patterns) {
        try {
            String code = Files.readString(path, StandardCharsets.UTF_8);
            for (String pattern : patterns) {
                if (code.contains(pattern)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
