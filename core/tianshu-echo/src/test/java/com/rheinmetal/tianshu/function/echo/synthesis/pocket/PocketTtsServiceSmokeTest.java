package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.function.echo.synthesis.moss.WavWriter;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketTtsServiceSmokeTest {
    @Test
    void synthesizesChineseReferenceSample() throws Exception {
        Path modelDir = modelDir();
        Path voice = voice(modelDir);
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("flowlm_step.onnx")), "Pocket model is missing");
        Assumptions.assumeTrue(Files.isRegularFile(voice), "Pocket reference voice is missing");

        FakeGameEnvironment env = new FakeGameEnvironment();
        try (PocketTtsService service = new PocketTtsService(
                env, modelDir, InferenceResourcePolicy.fixedProcessors(processors()))) {
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

    @Test
    void streamsChineseReferenceSample() throws Exception {
        Path modelDir = modelDir();
        Path voice = voice(modelDir);
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("flowlm_step.onnx")), "Pocket model is missing");
        Assumptions.assumeTrue(Files.isRegularFile(voice), "Pocket reference voice is missing");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        TtsCodecExecution lane = work -> {
            Future<?> future = executor.submit(work);
            return new TtsCodecExecution.Task() {
                @Override
                public boolean accepted() {
                    return true;
                }

                @Override
                public void cancel(String reason) {
                    future.cancel(true);
                }

                @Override
                public void await(BooleanSupplier cancellationRequested) throws Exception {
                    while (true) {
                        try {
                            future.get(50, TimeUnit.MILLISECONDS);
                            return;
                        } catch (TimeoutException timeout) {
                            if (cancellationRequested.getAsBoolean()) {
                                future.cancel(true);
                            }
                        }
                    }
                }
            };
        };
        FakeGameEnvironment env = new FakeGameEnvironment();
        try (PocketTtsService service = new PocketTtsService(
                env, modelDir, InferenceResourcePolicy.fixedProcessors(processors()), lane)) {
            List<float[]> chunks = new ArrayList<>();
            long[] firstAudioNanos = {0};
            long start = System.nanoTime();
            service.synthesizeStreaming("今天天气很好，适合外出散步。", voice, chunk -> {
                if (firstAudioNanos[0] == 0) {
                    firstAudioNanos[0] = System.nanoTime();
                }
                chunks.add(chunk);
            }, () -> false);
            long elapsed = System.nanoTime() - start;
            int samples = chunks.stream().mapToInt(chunk -> chunk.length).sum();
            assertTrue(samples > 0, "Pocket stream produced no audio");
            float[] merged = new float[samples];
            int offset = 0;
            for (float[] chunk : chunks) {
                System.arraycopy(chunk, 0, merged, offset, chunk.length);
                offset += chunk.length;
            }
            Path output = modelDir.resolve("pocket-smoke-stream.wav");
            WavWriter.writeWaveFile(output, new float[][]{merged}, service.sampleRate());
            double audioMillis = samples * 1000.0 / service.sampleRate();
            System.out.println("Pocket stream: chunks=" + chunks.size()
                    + " samples=" + samples
                    + " audioMs=" + Math.round(audioMillis)
                    + " firstAudioMs=" + (firstAudioNanos[0] - start) / 1_000_000
                    + " totalMs=" + elapsed / 1_000_000
                    + " rtf=" + String.format(java.util.Locale.ROOT, "%.3f", elapsed / 1_000_000.0 / audioMillis)
                    + " out=" + output);
        } finally {
            executor.shutdownNow();
        }
    }

    private static int processors() {
        return Integer.getInteger("tianshu.pocket.processors", 4);
    }

    private static Path modelDir() {
        String enabled = System.getProperty("tianshu.pocket.smoke", System.getenv("TIANSHU_POCKET_SMOKE"));
        Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "Set -Dtianshu.pocket.smoke=true (or TIANSHU_POCKET_SMOKE=true) to run the real Pocket-TTS smoke test");
        return Path.of(System.getProperty("tianshu.pocket.modelDir",
                Path.of("..", "libs", "pocket-tts-zh-4l-onnx").toString()));
    }

    private static Path voice(Path modelDir) {
        return Path.of(System.getProperty("tianshu.pocket.voice", modelDir.resolve("kefu.wav").toString()));
    }
}
