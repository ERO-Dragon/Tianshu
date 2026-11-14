package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.function.echo.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.echo.synthesis.moss.WavWriter;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Opt-in cross-engine RTF benchmark. Run with {@code -Dtianshu.tts.rtf=true}; it iterates the
 * installed catalog models, warms each backend once, then reports three full-synthesis runs.
 */
class AllTtsRtfSmokeTest {
    private static final String TEXT = "娆㈣繋鏉ュ埌鑰佸寳浜皯淇楀睍锛岃璺熸垜涓€璧风湅杩欎欢灞曞搧";
    private static final Path MODEL_BASE = Path.of(System.getProperty("tianshu.tts.modelBase",
            Path.of("..", "tianshu-adapter", "1.21.1-neoforge", "run", "config", "Tianshu", "module", "tts", "model").toString()));
    private static final Path VOICES = Path.of(System.getProperty("tianshu.tts.voices",
            Path.of("..", "tianshu-adapter", "1.21.1-neoforge", "run", "config", "Tianshu", "module", "tts", "voices").toString()));

    @Test
    void benchmarksInstalledTtsModels() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("tianshu.tts.rtf"),
                "Set -Dtianshu.tts.rtf=true to run the real cross-engine RTF benchmark");
        com.rheinmetal.tianshu.foundry.nativelib.NativeLibraryLoader.ensureLoaded();
        FakeGameEnvironment env = new FakeGameEnvironment();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TtsCodecExecution lane = lane(executor);
        TtsEngineProvider provider = new TtsEngineProvider(env, lane);
        try {
            Map<String, Path> models = installedModels();
            System.out.println("TTS_RTF models=" + models.keySet());
            String filter = System.getProperty("tianshu.tts.models", "");
            for (Map.Entry<String, Path> entry : models.entrySet()) {
                if (!filter.isBlank() && !filter.contains(entry.getKey())) {
                    continue;
                }
                benchmark(env, provider, entry.getKey(), entry.getValue());
            }
        } finally {
            provider.shutdown();
            executor.shutdownNow();
        }
    }

    private static void benchmark(FakeGameEnvironment env, TtsEngineProvider provider, String name, Path dir)
            throws Exception {
        TtsModelInfo info = find(name);
        if (info == null) {
            System.out.println("TTS_RTF skip " + name + ": missing catalog entry");
            return;
        }
        TtsResolvedModel model = new TtsResolvedModel(dir, info, null);
        long loadStart = System.nanoTime();
        TtsEngineProvider.Load load = provider.acquire(new LocalTtsBackendSelection(model), true);
        if (load.outcome() == TtsEngineProvider.Outcome.FAILED) {
            System.out.println("TTS_RTF skip " + name + ": backend initialization failed " + env.errors);
            return;
        }
        long loadMillis = (System.nanoTime() - loadStart) / 1_000_000L;
        TtsBackend backend = load.backend();
        TtsVoiceProfile voice = voiceFor(name, dir);

        try {
            backend.preloadVoice(voice);
            int runs = 3;
            List<Long> rtfMillis = new ArrayList<>();
            List<Long> firstMillis = new ArrayList<>();
            List<Long> audioMillis = new ArrayList<>();
            CapturingSink lastSink = null;
            for (int i = 0; i < runs + 1; i++) {
                CapturingSink sink = new CapturingSink();
                lastSink = sink;
                long start = System.nanoTime();
                TtsRequest request = new TtsRequest(
                        "rtf-" + name + "-" + i, "rtf", "rtf", "rtf", TEXT,
                        TtsRequestSource.UNKNOWN, TtsPlaybackPolicy.QUEUE, Priority.NORMAL, voice);
                backend.synthesize(request, sink);
                long elapsed = System.nanoTime() - start;
                if (i == 0) {
                    continue;
                }
                long audio = sink.audioMillis(backend.sampleRate());
                rtfMillis.add(elapsed / 1_000_000L);
                firstMillis.add(sink.firstAudioMillis());
                audioMillis.add(audio);
            }
            System.out.println("TTS_RTF model=" + name
                    + " loadMs=" + loadMillis
                    + " runs=" + runs
                    + " audioMs=" + average(audioMillis)
                    + " firstAudioMs=" + average(firstMillis)
                    + " synthMs=" + average(rtfMillis)
                    + " rtf=" + String.format(java.util.Locale.ROOT, "%.3f",
                    average(rtfMillis) / (double) average(audioMillis)));
            if (lastSink != null && Boolean.getBoolean("tianshu.tts.wavSamples")) {
                Path output = Path.of("..", "build", "rtf-samples", name + ".wav");
                Files.createDirectories(output.getParent());
                WavWriter.writeWaveFile(output, new float[][]{lastSink.pcmSamples()}, backend.sampleRate());
                System.out.println("TTS_RTF sample=" + output.toAbsolutePath());
            }
        } finally {
            provider.shutdown();
        }
    }


    private static TtsVoiceProfile voiceFor(String name, Path dir) {
        if ("MOSS-TTS-Nano-100M-ONNX".equals(name) || "PocketTTS-ZH-4L-ONNX".equals(name)) {
            return new TtsVoiceProfile("", 1f, 0, VOICES.resolve("moss_sample_1.wav").toAbsolutePath().toString());
        }
        return new TtsVoiceProfile("", 1f, 0, "");
    }

    private static Map<String, Path> installedModels() {
        Map<String, Path> models = new LinkedHashMap<>();
        addIfPresent(models, "MOSS-TTS-Nano-100M-ONNX", MODEL_BASE.resolve("MOSS-TTS-Nano-100M-ONNX"));
        addIfPresent(models, "vits-melo-tts-zh_en", MODEL_BASE.resolve("vits-melo-tts-zh_en"));
        addIfPresent(models, "kokoro-multi-lang-v1_1", MODEL_BASE.resolve("kokoro-multi-lang-v1_1"));
        addIfPresent(models, "PocketTTS-ZH-4L-ONNX", Path.of(System.getProperty("tianshu.tts.pocketDir",
                Path.of("..", "libs", "pocket-tts-zh-4l-onnx").toString())));
        return models;
    }

    private static void addIfPresent(Map<String, Path> models, String name, Path dir) {
        if (Files.isDirectory(dir) && Files.exists(dir.resolve("tokens.txt"))) {
            models.put(name, dir);
        } else if (Files.isDirectory(dir) && Files.exists(dir.resolve("browser_poc_manifest.json"))) {
            models.put(name, dir);
        } else if (Files.isDirectory(dir) && Files.exists(dir.resolve("flowlm_step.onnx"))) {
            models.put(name, dir);
        } else if (Files.isDirectory(dir) && Files.exists(dir.resolve("model.onnx"))) {
            models.put(name, dir);
        }
    }

    private static TtsModelInfo find(String name) {
        for (TtsModelInfo info : TtsModelInfo.loadCatalog()) {
            if (name.equals(info.name)) {
                return info;
            }
        }
        return null;
    }

    private static long average(List<Long> values) {
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static TtsCodecExecution lane(ExecutorService executor) {
        return work -> {
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
    }

    private static final class CapturingSink implements TtsAudioSink {
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final long start = System.nanoTime();
        private long firstAudioNanos;

        @Override
        public void accept(byte[] audio) {
            if (audio == null || audio.length == 0) {
                return;
            }
            if (firstAudioNanos == 0) {
                firstAudioNanos = System.nanoTime();
            }
            pcm.writeBytes(audio);
        }

        @Override
        public TtsSynthesisMode preferredSynthesisMode() {
            return TtsSynthesisMode.FULL;
        }

        long audioMillis(int sampleRate) {
            return (pcm.size() / 2L) * 1000L / Math.max(1, sampleRate);
        }

        long firstAudioMillis() {
            return firstAudioNanos == 0 ? 0 : (firstAudioNanos - start) / 1_000_000L;
        }

        float[] pcmSamples() {
            byte[] bytes = pcm.toByteArray();
            float[] samples = new float[bytes.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8)) / 32768f;
            }
            return samples;
        }
    }
}
