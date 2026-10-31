package com.rheinmetal.tianshu.model.lifecycle;

import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One gate, three lanes, one instance.
 *
 * <p>The whole point of {@link NativeModelLoadQueue} is that native peaks add up: two models loading at
 * once need both weight sets resident, which is the failure this class exists to prevent. That property
 * does not survive a second entry point, so the invariants are pinned here rather than left to review:
 * one shared instance, exactly one request call site per lane, and the primary claim owned by the
 * retrieval lane alone.</p>
 *
 * <p>Source-level on purpose. A lane can bypass the gate while still compiling — a direct
 * {@code engine.initialize()} does not fail a build — so the compiler cannot state this rule.</p>
 */
class NativeModelLoadGateCallSiteBoundaryTest {

    /** Repository root, relative to this module's test working directory. */
    private static final Path REPO = Path.of("..");

    private static final Path LLM_LANE =
            Path.of("../tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/LlmEngineProvider.java");
    private static final Path LLM_MODULE =
            Path.of("../tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/LlmModule.java");
    private static final Path ASR_LANE =
            Path.of("../tianshu-intake/src/main/java/com/rheinmetal/tianshu/function/intake/AsrModule.java");
    private static final Path TTS_LANE =
            Path.of("../tianshu-echo/src/main/java/com/rheinmetal/tianshu/function/echo/runtime/TtsModelLifecycleCoordinator.java");

    @Test
    void theGateIsConstructedOnceInTheWholeBuild() throws Exception {
        List<Path> sites = new ArrayList<>();
        for (Path source : productionSources()) {
            if (Files.readString(source, StandardCharsets.UTF_8).contains("new NativeModelLoadQueue(")) {
                sites.add(source.toAbsolutePath().normalize());
            }
        }

        // One gate per process. Every loader has its own composition root, so the rule is "the assembly owns
        // the gate and nothing else does": a module or a lane constructing its own would let two lanes load
        // native models at the same time, which is exactly what this gate exists to prevent. The build ships
        // one bootstrap per adapter version, so the rule is one construction per root directory, not one
        // file name in the whole tree.
        assertEquals(sites.size(), sites.stream().map(Path::getParent).distinct().count(),
                "one construction per composition root, never two in the same root: " + sites);
        for (Path site : sites) {
            assertTrue(site.getFileName().toString().endsWith("ClientBootstrap.java"),
                    site + " is not a composition root; the gate belongs to the assembly, not to a module");
        }
    }

    @Test
    void everyLaneAsksTheGateExactlyOnce() throws Exception {
        for (Path lane : List.of(LLM_LANE, ASR_LANE, TTS_LANE)) {
            assertTrue(Files.isRegularFile(lane), "lane source must be readable: " + lane);
            assertEquals(1, occurrences(read(lane), "loadQueue.request("),
                    lane.getFileName() + " must have exactly one gated entry, so no path can read a model around it");
        }
    }

    @Test
    void onlyTheRetrievalLaneClaimsThePrimaryPermit() throws Exception {
        // Two claim sites on purpose: the module announces the claim at world entry, and the provider
        // re-takes it when a restart replaces the engine. Both are the retrieval lane.
        assertEquals(1, occurrences(read(LLM_MODULE), "loadQueue.claimPrimary()"));
        assertEquals(1, occurrences(read(LLM_LANE), "loadQueue.claimPrimary()"));
        // Two settle sites on purpose, one per way the retrieval load can end without running: no engine
        // could be prepared, or the gate refused the request. Either way the deferred lane must be freed,
        // which is what the M6 refusal bug was about.
        assertEquals(2, occurrences(read(LLM_LANE), "loadQueue.settlePrimary()"),
                "every exit that cannot load must settle the claim, so the deferred lane is never left waiting");
        assertEquals(1, occurrences(read(LLM_LANE), "loadQueue.abandonPrimary()"));

        for (Path speechLane : List.of(ASR_LANE, TTS_LANE)) {
            String source = read(speechLane);
            assertFalse(source.contains("claimPrimary()") || source.contains("settlePrimary()")
                            || source.contains("abandonPrimary()"),
                    speechLane.getFileName() + " is a deferred lane: a speech module that claimed or settled the"
                            + " primary permit would decide when retrieval may run");
        }
    }

    @Test
    void releaseRequiresTheCallerToNameItself() {
        // The no-argument release is gone: it let a module hand back a permit it did not hold, which
        // silently granted a permit the gate never issued. Only the owner-checked form exists now.
        assertThrows(NoSuchMethodException.class, () -> NativeModelLoadQueue.class.getDeclaredMethod("release"),
                "release() must stay owner-checked: release(String) is the only form");
        assertEquals(1L, Stream.of(NativeModelLoadQueue.class.getDeclaredMethods())
                .filter(method -> "release".equals(method.getName()))
                .count());
    }

    /** Every {@code src/main/java} tree in the build, excluding outputs and the dev-client directory. */
    private static List<Path> productionSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(REPO)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalized(path).contains("/src/main/java/"))
                    .filter(path -> !normalized(path).contains("/build/"))
                    .filter(path -> !normalized(path).contains("/bin/"))
                    .filter(path -> !normalized(path).contains("/run/"))
                    .forEach(sources::add);
        }
        assertTrue(sources.size() > 50, "the repository walk must actually find the sources: " + sources.size());
        return sources;
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String read(Path path) throws IOException {
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
