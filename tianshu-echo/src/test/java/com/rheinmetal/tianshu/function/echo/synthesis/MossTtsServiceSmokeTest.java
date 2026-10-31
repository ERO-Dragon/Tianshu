package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTtsServiceSmokeTest {
    @Test
    void synthesizesShortVoiceCloneSample() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(setting("tianshu.moss.smoke", "TIANSHU_MOSS_SMOKE")),
                "Set -Dtianshu.moss.smoke=true (or TIANSHU_MOSS_SMOKE=true) to run the real MOSS-TTS benchmark");
        NativeLibraryLoader.ensureLoaded();

        Path modelDir = smokeModelDir();
        Path voiceSample = smokeVoiceSample(modelDir);
        Path outputDir = smokeOutputDir(modelDir);

        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("browser_poc_manifest.json")), "MOSS model manifest is missing");
        Assumptions.assumeTrue(Files.isRegularFile(voiceSample), "MOSS reference voice sample is missing");

        FakeGameEnvironment env = new FakeGameEnvironment();

        List<String> texts = smokeTexts();
        String cadenceName = smokeCadenceName();
        MossStreamingDecodeCadence cadence = smokeCadence();
        int preRollMillis = smokePreRollMillis();
        for (int processors : smokeProcessorSet()) {
            long initStart = System.nanoTime();
            InferenceResourcePolicy resourcePolicy = InferenceResourcePolicy.fixedProcessors(processors);
            try (SmokeCodecExecution codecExecution = new SmokeCodecExecution();
                 MossTtsService service = new MossTtsService(
                         env,
                         modelDir,
                         resourcePolicy,
                         codecExecution,
                         cadence
                 )) {
            service.init();
            long initMillis = elapsedMillis(initStart);

            String promptSource = smokePromptSource();
            String voiceName = smokeVoiceName();
            long promptStart = System.nanoTime();
            List<List<Integer>> promptAudioCodes = "builtin".equalsIgnoreCase(promptSource)
                    ? service.resolveBuiltinVoicePromptAudioCodes(voiceName)
                    : service.encodePromptAudioCodes(voiceSample);
            long promptMillis = elapsedMillis(promptStart);
            assertFalse(promptAudioCodes.isEmpty());

            List<Long> synthMillisList = new ArrayList<>();
            List<Long> firstAudioMillisList = new ArrayList<>();
            List<Long> generateMillisList = new ArrayList<>();
            List<Long> decodeMillisList = new ArrayList<>();
            List<Integer> frameCounts = new ArrayList<>();
            List<Integer> streamChunkCounts = new ArrayList<>();
            List<Integer> sampleCounts = new ArrayList<>();
            List<Long> audioMillisList = new ArrayList<>();
            List<Double> rtfList = new ArrayList<>();
            Files.createDirectories(outputDir);
            boolean streaming = smokeStreaming();
            long synthAllStart = System.nanoTime();
            for (int i = 0; i < texts.size(); i++) {
                List<float[][]> chunks = new ArrayList<>();
                applyPinnedSeed(service);
                long synthStart = System.nanoTime();
                AtomicLong firstAudioMillis = new AtomicLong(-1L);
                long generateMillis = -1L;
                long decodeMillis = -1L;
                int frameCount = -1;
                if (streaming) {
                    service.synthesizeStreaming(texts.get(i), promptAudioCodes, (audio, chunkIndex, totalChunks) -> {
                        firstAudioMillis.compareAndSet(-1L, elapsedMillis(synthStart));
                        if (chunks.isEmpty() && preRollMillis > 0) {
                            chunks.add(new float[audio.length][service.getSampleRate() * preRollMillis / 1000]);
                        }
                        chunks.add(audio);
                    });
                } else {
                    MossTtsService.SynthesisResult result = service.synthesizeSingleChunkDetailed(texts.get(i), promptAudioCodes);
                    firstAudioMillis.compareAndSet(-1L, elapsedMillis(synthStart));
                    chunks.add(result.channels);
                    generateMillis = result.generateMillis;
                    decodeMillis = result.decodeMillis;
                    frameCount = result.generatedFrameCount;
                }
                long synthMillis = elapsedMillis(synthStart);
                float[][] merged = merge(chunks);
                assertTrue(merged.length > 0 && merged[0].length > 0, "MOSS synthesis returned empty audio for text " + (i + 1));
                String preRollSuffix = preRollMillis > 0 ? "-pre" + preRollMillis : "";
                Path output = outputDir.resolve("moss-smoke-" + cadenceName + preRollSuffix + "-" + promptSource + "-p" + processors + "-" + (i + 1) + ".wav");
                WavWriter.writeWaveFile(output, merged, service.getSampleRate());
                assertTrue(Files.size(output) > 44, "MOSS output wav is empty: " + output);
                synthMillisList.add(synthMillis);
                firstAudioMillisList.add(firstAudioMillis.get());
                generateMillisList.add(generateMillis);
                decodeMillisList.add(decodeMillis);
                frameCounts.add(frameCount);
                streamChunkCounts.add(chunks.size());
                sampleCounts.add(merged[0].length);
                long audioMillis = merged[0].length * 1000L / service.getSampleRate();
                double rtf = audioMillis <= 0L ? Double.POSITIVE_INFINITY : synthMillis / (double) audioMillis;
                audioMillisList.add(audioMillis);
                rtfList.add(rtf);
                double maxRtf = smokeMaxRtf();
                if (Double.isFinite(maxRtf)) {
                    assertTrue(rtf <= maxRtf, "MOSS RTF " + rtf + " exceeded configured maximum " + maxRtf);
                }
            }
            long synthAllMillis = elapsedMillis(synthAllStart);

            System.out.println("MOSS smoke result:");
            System.out.println("  modelDir=" + modelDir.toAbsolutePath().normalize());
            System.out.println("  voiceSample=" + voiceSample.toAbsolutePath().normalize());
            System.out.println("  outputDir=" + outputDir.toAbsolutePath().normalize());
            System.out.println("  promptSource=" + promptSource);
            System.out.println("  voice=" + voiceName);
            System.out.println("  streaming=" + streaming);
            System.out.println("  cadence=" + cadenceName);
            System.out.println("  preRollMillis=" + preRollMillis);
            System.out.println("  sampleRate=" + service.getSampleRate());
            System.out.println("  processors=" + processors);
            System.out.println("  mossThreads=" + resourcePolicy.mossTtsThreads());
            System.out.println("  promptFrames=" + promptAudioCodes.size());
            System.out.println("  initMillis=" + initMillis);
            System.out.println("  promptMillis=" + promptMillis);
            for (int i = 0; i < texts.size(); i++) {
                System.out.println("  sentence" + (i + 1) + "Millis=" + synthMillisList.get(i)
                        + ", firstAudioMillis=" + firstAudioMillisList.get(i)
                        + ", generateMillis=" + generateMillisList.get(i)
                        + ", decodeMillis=" + decodeMillisList.get(i)
                        + ", frames=" + frameCounts.get(i)
                        + ", streamChunks=" + streamChunkCounts.get(i)
                        + ", samples=" + sampleCounts.get(i)
                        + ", audioMillis=" + audioMillisList.get(i)
                        + ", rtf=" + String.format(java.util.Locale.ROOT, "%.4f", rtfList.get(i))
                        + ", text=" + texts.get(i));
            }
            System.out.println("  synthAllMillis=" + synthAllMillis);
            System.out.println("  totalMillis=" + elapsedMillis(initStart));
            }
        }
    }

    private static Path resolveExistingPath(Path first, Path second) {
        if (Files.exists(first)) {
            return first.normalize();
        }
        return second.normalize();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Resets the frame generator RNG to a fixed seed before each synthesis so repeated benchmark
     * runs produce byte-identical audio. Without this, utterance length varies per run and RTF
     * differences between configurations are dominated by that variation instead of the hot path.
     */
    private static void applyPinnedSeed(MossTtsService service) {
        String configured = setting("tianshu.moss.seed", "TIANSHU_MOSS_SEED");
        if (configured.isBlank()) {
            return;
        }
        long seed;
        try {
            seed = Long.parseLong(configured);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid MOSS benchmark seed: " + configured, invalid);
        }
        service.setGenerationSeed(seed);
    }

    private static Path smokeModelDir() {
        String configured = setting("tianshu.moss.modelDir", "TIANSHU_MOSS_MODEL_DIR");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return resolveExistingPath(
                Path.of("libs", "MOSS-TTS-Nano-main", "model"),
                Path.of("..", "libs", "MOSS-TTS-Nano-main", "model")
        );
    }

    private static Path smokeVoiceSample(Path modelDir) {
        String configured = setting("tianshu.moss.voiceWav", "TIANSHU_MOSS_VOICE_WAV");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return modelDir.resolve("\uD83C\uDDE8\uD83C\uDDF3 \u6B22\u8FCE\u5173\u6CE8\u6A21\u601D\u667A\u80FD.wav");
    }

    private static Path smokeOutputDir(Path modelDir) {
        String configured = setting("tianshu.moss.outputDir", "TIANSHU_MOSS_OUTPUT_DIR");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return modelDir.resolve("moss-smoke-output");
    }

    /** Reads an explicit system property first, then the environment variable for the same setting. */
    private static String setting(String propertyKey, String environmentKey) {
        String property = System.getProperty(propertyKey);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String environment = System.getenv(environmentKey);
        return environment == null ? "" : environment.trim();
    }

    private static int smokeProcessors() {
        String value = setting("tianshu.moss.processors", "TIANSHU_MOSS_PROCESSORS");
        if (value.isBlank()) {
            return Runtime.getRuntime().availableProcessors();
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private static List<Integer> smokeProcessorSet() {
        String value = setting("tianshu.moss.processorSet", "TIANSHU_MOSS_PROCESSOR_SET");
        if (value.isBlank()) {
            return List.of(smokeProcessors());
        }
        List<Integer> processors = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                processors.add(Math.max(1, Integer.parseInt(part.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return processors.isEmpty() ? List.of(smokeProcessors()) : processors;
    }

    private static String smokePromptSource() {
        String value = setting("tianshu.moss.promptSource", "TIANSHU_MOSS_PROMPT_SOURCE");
        if (value.isBlank()) {
            return "reference";
        }
        return "builtin".equals(value.toLowerCase(java.util.Locale.ROOT)) ? "builtin" : "reference";
    }

    private static String smokeVoiceName() {
        String value = setting("tianshu.moss.voice", "TIANSHU_MOSS_VOICE");
        return value.isBlank() ? "Junhao" : value;
    }

    private static boolean smokeStreaming() {
        return "true".equalsIgnoreCase(setting("tianshu.moss.streaming", "TIANSHU_MOSS_STREAMING"));
    }

    private static String smokeCadenceName() {
        String value = setting("tianshu.moss.cadence", "TIANSHU_MOSS_CADENCE");
        if (value.isBlank()) {
            return "fixed4";
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "fixed8" -> "fixed8";
            case "mixed", "first4then16" -> "mixed";
            case "upstream", "adaptive", "1-2-4-8" -> "upstream";
            default -> "fixed4";
        };
    }

    private static MossStreamingDecodeCadence smokeCadence() {
        return switch (smokeCadenceName()) {
            case "fixed8" -> MossStreamingDecodeCadence.fixed(8);
            case "mixed" -> MossStreamingDecodeCadence.firstChunkThenBatched(4, 8);
            case "upstream" -> MossStreamingDecodeCadence.upstreamAdaptive();
            default -> MossStreamingDecodeCadence.fixed(4);
        };
    }

    private static int smokePreRollMillis() {
        String value = setting("tianshu.moss.preRollMillis", "TIANSHU_MOSS_PREROLL_MS");
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<String> smokeTexts() {
        List<String> baseTexts = parseTexts(setting("tianshu.moss.texts", "TIANSHU_MOSS_TEXTS"));
        if (baseTexts.isEmpty()) {
            baseTexts = List.of(
                    "\u4F60\u597D\uFF0C\u6211\u662F\u5929\u67A2\u4EBA\u5DE5\u667A\u80FD\u52A9\u624B\u3002",
                    "\u597D\u7684\uFF0C\u6211\u5DF2\u7ECF\u8BB0\u4E0B\u4E86\u3002",
                    "\u73B0\u5728\u53EF\u4EE5\u7EE7\u7EED\u8BF4\u4E0B\u4E00\u4EF6\u4E8B\u3002"
            );
        }
        int repeat = smokeRepeat();
        List<String> texts = new ArrayList<>(baseTexts.size() * repeat);
        for (int i = 0; i < repeat; i++) {
            texts.addAll(baseTexts);
        }
        return texts;
    }

    private static List<String> parseTexts(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (String part : value.split("\\|")) {
            String text = part.trim();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private static int smokeRepeat() {
        String value = setting("tianshu.moss.repeat", "TIANSHU_MOSS_REPEAT");
        if (value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static double smokeMaxRtf() {
        String value = setting("tianshu.moss.maxRtf", "TIANSHU_MOSS_MAX_RTF");
        if (value.isBlank()) {
            return Double.NaN;
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 0.0D ? parsed : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static float[][] merge(List<float[][]> chunks) {
        if (chunks.isEmpty()) {
            return new float[][]{new float[0]};
        }
        int channels = chunks.get(0).length;
        int samples = 0;
        for (float[][] chunk : chunks) {
            if (chunk.length > 0) {
                samples += chunk[0].length;
            }
        }
        float[][] merged = new float[channels][samples];
        int offset = 0;
        for (float[][] chunk : chunks) {
            if (chunk.length == 0) {
                continue;
            }
            int length = chunk[0].length;
            for (int channel = 0; channel < channels; channel++) {
                float[] source = chunk[Math.min(channel, chunk.length - 1)];
                System.arraycopy(source, 0, merged[channel], offset, Math.min(length, source.length));
            }
            offset += length;
        }
        return merged;
    }

    private static final class SmokeCodecExecution implements TtsCodecExecution, AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public Task submit(Runnable work) {
            Future<?> future = executor.submit(work);
            return new Task() {
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
                        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                            future.cancel(true);
                            throw new CancellationException("TTS_MOSS_SMOKE_CANCELLED");
                        }
                        try {
                            future.get(25L, TimeUnit.MILLISECONDS);
                            return;
                        } catch (TimeoutException ignored) {
                        } catch (ExecutionException failure) {
                            Throwable cause = failure.getCause();
                            if (cause instanceof Exception exception) {
                                throw exception;
                            }
                            if (cause instanceof Error error) {
                                throw error;
                            }
                            throw new IllegalStateException(cause);
                        }
                    }
                }
            };
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
