package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.synthesis.moss.WavWriter;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketTtsServiceSmokeTest {
    @Test
    void synthesizesChineseReferenceSample() throws Exception {
        String enabled = System.getProperty("tianshu.pocket.smoke",
                System.getenv("TIANSHU_POCKET_SMOKE"));
        Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "Set -Dtianshu.pocket.smoke=true (or TIANSHU_POCKET_SMOKE=true) to run the real Pocket-TTS smoke test");

        Path modelDir = Path.of(System.getProperty("tianshu.pocket.modelDir",
                Path.of("..", "libs", "pocket-tts-zh-4l-onnx").toString()));
        Path voice = Path.of(System.getProperty("tianshu.pocket.voice",
                modelDir.resolve("kefu.wav").toString()));
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("flowlm_step.onnx")), "Pocket model is missing");
        Assumptions.assumeTrue(Files.isRegularFile(voice), "Pocket reference voice is missing");

        FakeGameEnvironment env = new FakeGameEnvironment();
        try (PocketTtsService service = new PocketTtsService(
                env, modelDir, InferenceResourcePolicy.fixedProcessors(4))) {
            long start = System.nanoTime();
            float[][] audio = service.synthesizeToWaveform(
                    "今天天气很好，适合外出散步。", voice, () -> false);
            long elapsed = System.nanoTime() - start;
            assertTrue(audio.length == 1 && audio[0].length > 0, "Pocket output is empty");
            Path output = modelDir.resolve("pocket-smoke.wav");
            WavWriter.writeWaveFile(output, audio, service.sampleRate());
            double audioMillis = audio[0].length * 1000.0 / service.sampleRate();
            System.out.println("Pocket smoke: samples=" + audio[0].length
                    + " audioMs=" + Math.round(audioMillis)
                    + " synthMs=" + elapsed / 1_000_000
                    + " rtf=" + String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1_000_000.0 / audioMillis)
                    + " out=" + output);
        }
    }
}
