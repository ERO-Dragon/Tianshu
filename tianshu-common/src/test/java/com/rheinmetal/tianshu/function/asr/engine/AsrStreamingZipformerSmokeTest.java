package com.rheinmetal.tianshu.function.asr.engine;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrStreamingZipformerSmokeTest {
    private static final String MODEL_KEY = "sherpa-onnx-streaming-zipformer-zh-xlarge-int8-2025-06-30";
    @Test
    void initializesStreamingEngineAndFlushesMinimalAudio() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_ASR_SMOKE")),
                "Set TIANSHU_ASR_SMOKE=true to run the real streaming Zipformer smoke test");

        Path modelDir = resolveModelDir();
        AsrModelInfo modelInfo = AsrModelManager.getModelByLocalKey(MODEL_KEY);
        assertNotNull(modelInfo, "Streaming Zipformer model is missing from the ASR catalog");
        for (String file : modelInfo.getAllRequiredFiles()) {
            Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve(file)),
                    "Streaming Zipformer model file is missing: " + modelDir.resolve(file));
        }

        assertTrue(modelInfo.isStreamingModel());
        assertEquals("transducer", modelInfo.architecture());

        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        AsrEngine engine = new AsrEngine(env);
        AsrEngine.StreamingSession session = null;
        try {
            assertTrue(engine.initialize(modelInfo, modelDir), () -> "ASR engine initialization failed: " + env.errors);
            assertFalse(engine.isOffline());
            assertEquals("online-transducer", engine.configKind());
            assertTrue(engine.supportsStreamingRecognition());
            assertTrue(engine.supportsCompleteRecognition());

            session = engine.createStreamingSession();
            assertNotNull(session);
            engine.feedAudio(session, new byte[3_200]);
            assertNotNull(engine.forceFlush(session));
        } finally {
            if (session != null) {
                engine.releaseStreamingSession(session);
            }
            engine.shutdown();
        }
    }

    private static Path resolveModelDir() {
        String configured = System.getenv("TIANSHU_ASR_SMOKE_MODEL_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        List<Path> candidates = List.of(
                Path.of("libs", "asr-smoke", MODEL_KEY),
                Path.of("..", "libs", "asr-smoke", MODEL_KEY),
                Path.of("tianshu-neoforge", "run", "config", "Tianshu", "module", "asr", "model", MODEL_KEY),
                Path.of("..", "tianshu-neoforge", "run", "config", "Tianshu", "module", "asr", "model", MODEL_KEY)
        );
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(candidates.getFirst().toAbsolutePath().normalize());
    }
}
