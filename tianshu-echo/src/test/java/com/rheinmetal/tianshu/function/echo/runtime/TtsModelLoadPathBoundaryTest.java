package com.rheinmetal.tianshu.function.echo.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TTS model has exactly one way in, and a switch never closes the running backend first.
 *
 * <p>Both facts are invisible to the compiler. Reload, the model switch and the two preview switches used
 * to call the engine directly, so their native peaks could add up with the embedding load the shared gate
 * exists to serialise; and the switch used to close the running backend before loading its replacement,
 * which left the lane unable to speak — or permanently silent when the replacement turned out to be
 * broken. Each rule is pinned here at the level of the source, following the ASR load-path boundary test.</p>
 */
class TtsModelLoadPathBoundaryTest {

    private static final Path COORDINATOR =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo/runtime/TtsModelLifecycleCoordinator.java");
    private static final Path PROVIDER =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo/synthesis/TtsEngineProvider.java");
    private static final Path ENGINE =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo/synthesis/DefaultTtsSynthesisEngine.java");
    private static final Path RUNTIME =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo/runtime/TtsRuntime.java");
    private static final Path TASK_COORDINATOR =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo/runtime/TtsSynthesisTaskCoordinator.java");
    private static final Path MAIN_SOURCES =
            Path.of("src/main/java/com/rheinmetal/tianshu/function/echo");

    @Test
    void everyModelReadAsksTheGateOnce() throws Exception {
        assertEquals(1, occurrences(read(COORDINATOR), "loadQueue.request("),
                "one gate entry: a second one would let two lanes load native models at the same time");
    }

    /**
     * The synthesis lanes must never load the model.
     *
     * <p>Both used to call {@code initialize()} at the top of every synthesis, which is a native model read
     * on the synthesis thread — a second door that only opened when the lane was unprepared, and therefore
     * made "is the gate in place" impossible to observe from the outside. They ask
     * {@code isInitialized()} now and report the failure on the channel they already had.</p>
     */
    @Test
    void synthesisNeverLoadsTheModelItself() throws Exception {
        assertEquals(1, occurrences(read(ENGINE), "initialize()"),
                "the engine may only declare initialize(); synthesize() must not call it");

        String runtime = read(RUNTIME);
        assertTrue(!runtime.contains("synthesisEngine.initialize("),
                "the speech lanes must not load the model: an unprepared lane reports instead of loading");
        assertTrue(runtime.contains("!synthesisEngine.isInitialized()"),
                "the unprepared case has to be reported on the existing failure channel");

        String tasks = read(TASK_COORDINATOR);
        assertTrue(!tasks.contains("synthesisEngine.initialize("),
                "the synthesis task coordinator must not load the model either");
        assertTrue(tasks.contains("!synthesisEngine.isInitialized()"),
                "the unprepared case has to be reported on the existing failure channel");
    }

    @Test
    void theOnlyModelReadInTheModuleIsInsideTheManagedPath() throws Exception {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(MAIN_SOURCES)) {
            List<Path> sources = tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/build/"))
                    .toList();
            for (Path source : sources) {
                if (read(source).contains("synthesisEngine.initialize(")) {
                    sites.add(source.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("TtsModelLifecycleCoordinator.java"), sites,
                "the model read has exactly one caller: runPrepareWork, which runs under the shared permit");
    }

    @Test
    void everyModelTouchingOperationUsesTheManagedPath() throws Exception {
        String coordinator = read(COORDINATOR);
        // All whitespace stripped: the call sites are both inline and wrapped across lines, and a
        // formatting change must not be able to hide a missing managed-path call.
        String compact = coordinator.replaceAll("\\s+", "");

        // One definition plus one call site per operation family: prepare, the model switch/reload, the
        // preview switch and the preview restore. The count is the point — a new door bumps it.
        assertEquals(5, occurrences(coordinator, "requestManagedLoad("),
                "prepare / switch / preview-begin / preview-restore must all enter through requestManagedLoad");
        assertTrue(compact.contains("requestManagedLoad(\"prepare\""), "prepare has to take the managed path");
        assertTrue(compact.contains("requestManagedLoad(\"preview-begin\""),
                "the preview switch has to take the managed path");
        assertTrue(compact.contains("requestManagedLoad(\"preview-restore\""),
                "the preview restore has to take the managed path");
        assertTrue(compact.contains("requestManagedLoad(action,this::runModelSwitch"),
                "reload and the model switch have to take the managed path");
    }

    @Test
    void theBackendIsBuiltInExactlyOnePlace() throws Exception {
        String provider = read(PROVIDER);

        assertEquals(1, occurrences(provider, ".initialize(model)"),
                "a backend may be initialized from exactly one place, so every load is gated the same way");
        assertEquals(1, occurrences(provider, "= createBackend("),
                "one construction point: a second one would be a second door into native memory");
    }

    @Test
    void theRunningBackendIsClosedOnlyAfterTheReplacementIsUp() throws Exception {
        String provider = read(PROVIDER);

        int load = provider.indexOf(".initialize(model)");
        int closePrevious = provider.indexOf("previous.shutdown()");
        assertTrue(load >= 0, "the provider has to initialize the candidate");
        assertTrue(closePrevious > load,
                "the replacement must load before the running backend is closed, or a failed switch"
                        + " leaves a window with no voice at all");
    }

    @Test
    void theEngineAdoptsAModelOnlyAfterItLoaded() throws Exception {
        String engine = read(ENGINE);

        assertEquals(1, occurrences(engine, "engineProvider.acquire("),
                "initialize / reload / useModel must share the one transactional load");
        assertEquals(4, occurrences(engine, "applyModel("),
                "one definition plus the three entry points that adopt a model");
    }

    private static String read(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), "source must be readable: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
