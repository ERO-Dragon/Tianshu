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
        String client = Files.readString(
                neoforgeRoot.resolve("com/rheinmetal/tianshu/client/TianshuClient.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(client.contains("startRuntimeSession().whenComplete"));
        assertTrue(client.contains("stopRuntimeSession().whenComplete"));
        assertTrue(client.contains("whenComplete"));
        assertFalse(client.contains("coreManager.initWorkers()"));
        assertFalse(client.contains("coreManager.restartRuntimeAsync("));
        assertFalse(client.contains("coreManager.refreshRuntimeAsync("));

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
        String client = Files.readString(
                neoforgeRoot.resolve("com/rheinmetal/tianshu/client/TianshuClient.java"),
                StandardCharsets.UTF_8
        );
        Path modulePath = neoforgeRoot.resolve(
                "com/rheinmetal/tianshu/client/lifecycle/ClientOnnxRuntimeModule.java"
        );
        Path installerPath = neoforgeRoot.resolve(
                "com/rheinmetal/tianshu/client/lifecycle/ClientOnnxRuntimeModuleInstaller.java"
        );

        assertFalse(client.contains("ensureOnnxRuntimeLoaded()"));
        assertFalse(client.contains("OrtEnvironment.getEnvironment()"));
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
        Path clientRoot = Path.of("../tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client");
        String client = Files.readString(clientRoot.resolve("TianshuClient.java"), StandardCharsets.UTF_8);
        String manager = Files.readString(
                clientRoot.resolve("ir/ClientNamedObjectIndexManager.java"),
                StandardCharsets.UTF_8
        );
        String reloadListener = Files.readString(
                clientRoot.resolve("ir/NamedObjectReloadListener.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(client.contains("ClientNamedObjectIndexManager.initializeAsync("));
        assertTrue(client.contains("ClientNamedObjectIndexManager.close()"));
        assertFalse(client.contains("ClientNamedObjectIndexManager.ensureIndex(\"client login\")"));
        assertTrue(manager.contains("ExecutorService"));
        assertTrue(manager.contains("initializeAsync("));
        assertTrue(manager.contains("reloadAsync("));
        assertFalse(manager.contains("catch (Throwable throwable)"));
        assertTrue(reloadListener.contains("ClientNamedObjectIndexManager.reloadAsync("));
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
