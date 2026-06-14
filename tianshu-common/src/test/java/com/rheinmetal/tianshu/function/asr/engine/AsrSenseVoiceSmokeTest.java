package com.rheinmetal.tianshu.function.asr.engine;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AsrSenseVoiceSmokeTest {
    private static final String MODEL_KEY = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09";

    @Test
    void createsOfflineRecognizerFromDownloadedSenseVoiceModel() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_ASR_SMOKE")),
                "Set TIANSHU_ASR_SMOKE=true to run the real ASR SenseVoice smoke test");

        Path modelDir = resolveModelDir();
        Path model = modelDir.resolve("model.int8.onnx");
        Path tokens = modelDir.resolve("tokens.txt");
        Assumptions.assumeTrue(Files.isRegularFile(model), "SenseVoice model file is missing: " + model);
        Assumptions.assumeTrue(Files.isRegularFile(tokens), "SenseVoice tokens file is missing: " + tokens);

        OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
                .setModel(model.toAbsolutePath().normalize().toString())
                .setLanguage("zh")
                .setInverseTextNormalization(true)
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(senseVoice)
                .setTokens(tokens.toAbsolutePath().normalize().toString())
                .setNumThreads(2)
                .setDebug(false)
                .build();
        OfflineRecognizerConfig recognizerConfig = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .build();

        assertDoesNotThrow(() -> {
            OfflineRecognizer recognizer = new OfflineRecognizer(recognizerConfig);
            recognizer.release();
        });
    }

    @Test
    void createsOfflineRecognizerThroughAsrConfigFactory() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_ASR_SMOKE")),
                "Set TIANSHU_ASR_SMOKE=true to run the real ASR SenseVoice smoke test");

        Path modelDir = resolveModelDir();
        AsrModelInfo modelInfo = AsrModelManager.getModelByLocalKey(MODEL_KEY);
        Assumptions.assumeTrue(modelInfo != null, "SenseVoice model is missing from ASR catalog");
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("model.int8.onnx")), "SenseVoice model file is missing");
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("tokens.txt")), "SenseVoice tokens file is missing");

        SherpaOnnxAsrConfigFactory factory = new SherpaOnnxAsrConfigFactory(new TestLlmSupport.FakeGameEnvironment());
        SherpaOnnxAsrConfigFactory.ResolvedConfig resolved = factory.build(modelInfo, modelDir, null).orElseThrow();

        assertDoesNotThrow(() -> {
            OfflineRecognizer recognizer = new OfflineRecognizer(resolved.offlineConfig());
            recognizer.release();
        });
    }

    private static Path resolveModelDir() {
        return resolveExistingPath(
                Path.of("tianshu-neoforge", "run", "config", "Tianshu", "module", "asr", "model", MODEL_KEY),
                Path.of("..", "tianshu-neoforge", "run", "config", "Tianshu", "module", "asr", "model", MODEL_KEY)
        );
    }

    private static Path resolveExistingPath(Path first, Path second) {
        if (Files.exists(first)) {
            return first.normalize();
        }
        return second.normalize();
    }
}
