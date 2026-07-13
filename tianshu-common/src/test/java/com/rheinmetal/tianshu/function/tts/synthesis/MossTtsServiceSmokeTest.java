package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.function.tts.synthesis.moss.MossTtsService;
import com.rheinmetal.tianshu.function.tts.synthesis.moss.WavWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTtsServiceSmokeTest {
    @Test
    void synthesizesShortVoiceCloneSample() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_MOSS_SMOKE")),
                "Set TIANSHU_MOSS_SMOKE=true to run the real MOSS-TTS smoke test");
        NativeLibraryLoader.ensureLoaded();

        Path modelDir = resolveExistingPath(
                Path.of("libs", "MOSS-TTS-Nano-main", "model"),
                Path.of("..", "libs", "MOSS-TTS-Nano-main", "model")
        );
        Path voiceSample = modelDir.resolve("\uD83C\uDDE8\uD83C\uDDF3 \u6B22\u8FCE\u5173\u6CE8\u6A21\u601D\u667A\u80FD.wav");
        Path outputDir = modelDir.resolve("moss-smoke-output");

        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("browser_poc_manifest.json")), "MOSS model manifest is missing");
        Assumptions.assumeTrue(Files.isRegularFile(voiceSample), "MOSS reference voice sample is missing");

        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);

        List<String> texts = smokeTexts();
        for (int processors : smokeProcessorSet()) {
            long initStart = System.nanoTime();
            InferenceResourcePolicy resourcePolicy = InferenceResourcePolicy.fixedProcessors(processors);
            try (MossTtsService service = new MossTtsService(env, downloader, modelDir, resourcePolicy)) {
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
                long synthStart = System.nanoTime();
                AtomicLong firstAudioMillis = new AtomicLong(-1L);
                long generateMillis = -1L;
                long decodeMillis = -1L;
                int frameCount = -1;
                if (streaming) {
                    service.synthesizeStreaming(texts.get(i), promptAudioCodes, (audio, chunkIndex, totalChunks) -> {
                        firstAudioMillis.compareAndSet(-1L, elapsedMillis(synthStart));
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
                Path output = outputDir.resolve("moss-smoke-" + promptSource + "-p" + processors + "-" + (i + 1) + ".wav");
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

    private static int smokeProcessors() {
        String value = System.getenv("TIANSHU_MOSS_PROCESSORS");
        if (value == null || value.isBlank()) {
            return Runtime.getRuntime().availableProcessors();
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return Runtime.getRuntime().availableProcessors();
        }
    }

    private static List<Integer> smokeProcessorSet() {
        String value = System.getenv("TIANSHU_MOSS_PROCESSOR_SET");
        if (value == null || value.isBlank()) {
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
        String value = System.getenv("TIANSHU_MOSS_PROMPT_SOURCE");
        if (value == null || value.isBlank()) {
            return "reference";
        }
        String normalized = value.trim().toLowerCase();
        return "builtin".equals(normalized) ? "builtin" : "reference";
    }

    private static String smokeVoiceName() {
        String value = System.getenv("TIANSHU_MOSS_VOICE");
        return value == null || value.isBlank() ? "Junhao" : value.trim();
    }

    private static boolean smokeStreaming() {
        return "true".equalsIgnoreCase(System.getenv("TIANSHU_MOSS_STREAMING"));
    }

    private static List<String> smokeTexts() {
        List<String> baseTexts = parseTexts(System.getenv("TIANSHU_MOSS_TEXTS"));
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
        String value = System.getenv("TIANSHU_MOSS_REPEAT");
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static double smokeMaxRtf() {
        String value = System.getenv("TIANSHU_MOSS_MAX_RTF");
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
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
}
