package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsCodecExecution;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MossStreamingCodecEquivalenceSmokeTest {
    @Test
    void pipelinedCodecMatchesSerialCodecForIdenticalFrames() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_MOSS_SMOKE")),
                "Set TIANSHU_MOSS_SMOKE=true to run the real MOSS-TTS smoke test");
        NativeLibraryLoader.ensureLoaded();

        Path modelDir = resolveExistingPath(
                Path.of("libs", "MOSS-TTS-Nano-main", "model"),
                Path.of("..", "libs", "MOSS-TTS-Nano-main", "model")
        );
        Assumptions.assumeTrue(Files.isRegularFile(modelDir.resolve("browser_poc_manifest.json")),
                "MOSS model manifest is missing");

        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        InferenceResourcePolicy policy = InferenceResourcePolicy.fixedProcessors(smokeProcessors());
        try (MossModelRuntime runtime = new MossModelRuntime(env, new HuggingFaceDownloader(env), modelDir, policy);
             ThreadedCodecExecution execution = new ThreadedCodecExecution()) {
            runtime.initialize();
            MossFrameGenerator generator = new MossFrameGenerator(env, runtime);
            MossAudioCodec codec = new MossAudioCodec(env, runtime);
            String text = smokeText();
            int[] tokenIds = runtime.tokenizer().encode(text)
                    .stream()
                    .mapToInt(Integer::intValue)
                    .toArray();
            MossTtsService.RequestRows requestRows = generator.buildVoiceCloneRequestRows(
                    builtinVoice(runtime.manifest(), "Junhao"),
                    tokenIds
            );
            MossFrameGenerationResult generation = generator.generateAudioFrames(requestRows);
            List<List<Integer>> frames = generation.requireNaturalEnd(tokenIds.length);

            float[][] serial4 = decodeSerial(codec, frames, 4);
            float[][] pipelined4 = decodePipeline(codec, execution, frames, generation.maxFrameCount(), runtime.sampleRate(), 4);
            float[][] serial8 = decodeSerial(codec, frames, 8);
            float[][] pipelined8 = decodePipeline(codec, execution, frames, generation.maxFrameCount(), runtime.sampleRate(), 8);

            assertSameAudio(serial4, pipelined4);
            assertSameAudio(serial8, pipelined8);
            Path outputDir = modelDir.resolve("moss-smoke-output");
            WavWriter.writeWaveFile(outputDir.resolve("moss-equivalence-fixed4.wav"), pipelined4, runtime.sampleRate());
            WavWriter.writeWaveFile(outputDir.resolve("moss-equivalence-fixed8.wav"), pipelined8, runtime.sampleRate());
            System.out.println("MOSS codec equivalence: frames=" + frames.size()
                    + ", text=" + text
                    + ", fixed4Samples=" + (pipelined4.length == 0 ? 0 : pipelined4[0].length)
                    + ", fixed8Samples=" + (pipelined8.length == 0 ? 0 : pipelined8[0].length)
                    + ", fixed4Sha256=" + hash(pipelined4)
                    + ", fixed8Sha256=" + hash(pipelined8));
        }
    }

    private static float[][] decodeSerial(MossAudioCodec codec, List<List<Integer>> frames, int batchSize) throws Exception {
        List<float[][]> chunks = new ArrayList<>();
        try (MossAudioCodec.StreamingDecoder decoder = codec.openStreamingDecoder()) {
            for (int start = 0; start < frames.size(); start += batchSize) {
                int end = Math.min(frames.size(), start + batchSize);
                MossTtsService.DecodeResult decoded = decoder.decodeFrames(frames.subList(start, end));
                if (decoded.audioLength > 0) {
                    chunks.add(decoded.channels);
                }
            }
        }
        return merge(chunks);
    }

    private static float[][] decodePipeline(
            MossAudioCodec codec,
            TtsCodecExecution execution,
            List<List<Integer>> frames,
            int maxFrameCount,
            int sampleRate,
            int batchSize
    ) throws Exception {
        List<float[][]> chunks = new ArrayList<>();
        MossStreamingDecodePipeline pipeline = new MossStreamingDecodePipeline(
                4,
                execution,
                sampleRate,
                MossStreamingDecodeCadence.fixed(batchSize)
        );
        pipeline.run(
                (frameConsumer, cancellation) -> {
                    for (int index = 0; index < frames.size(); index++) {
                        frameConsumer.onFrame(index, frames.get(index));
                    }
                    return MossFrameGenerationResult.naturalEnd(frames, maxFrameCount);
                },
                () -> decoder(codec),
                chunks::add,
                () -> false
        );
        return merge(chunks);
    }

    private static void assertSameAudio(float[][] expected, float[][] actual) throws Exception {
        assertEquals(expected.length, actual.length);
        for (int channel = 0; channel < expected.length; channel++) {
            assertArrayEquals(expected[channel], actual[channel], 0.0f);
        }
        assertEquals(hash(expected), hash(actual));
    }

    private static String smokeText() {
        String value = System.getenv("TIANSHU_MOSS_EQUIV_TEXT");
        return value == null || value.isBlank() ? "你好，我是天枢人工智能助手。" : value.trim();
    }

    private static MossStreamingDecodePipeline.Decoder decoder(MossAudioCodec codec) throws Exception {
        MossAudioCodec.StreamingDecoder decoder = codec.openStreamingDecoder();
        return new MossStreamingDecodePipeline.Decoder() {
            @Override
            public List<float[][]> decode(List<List<Integer>> frames, boolean finalBatch) throws Exception {
                List<float[][]> chunks = new ArrayList<>();
                MossTtsService.DecodeResult decoded = decoder.decodeFrames(frames);
                if (decoded.audioLength > 0) {
                    chunks.add(decoded.channels);
                }
                return chunks;
            }

            @Override
            public void close() {
                decoder.close();
            }
        };
    }

    private static List<List<Integer>> builtinVoice(JsonObject manifest, String voiceName) {
        JsonArray voices = manifest.getAsJsonArray("builtin_voices");
        for (JsonElement voiceElement : voices) {
            JsonObject voice = voiceElement.getAsJsonObject();
            if (!voiceName.equalsIgnoreCase(voice.get("voice").getAsString())) {
                continue;
            }
            List<List<Integer>> frames = new ArrayList<>();
            for (JsonElement rowElement : voice.getAsJsonArray("prompt_audio_codes")) {
                List<Integer> row = new ArrayList<>();
                for (JsonElement token : rowElement.getAsJsonArray()) {
                    row.add(token.getAsInt());
                }
                frames.add(row);
            }
            return frames;
        }
        throw new IllegalStateException("TTS_MOSS_SMOKE_VOICE_NOT_FOUND voice=" + voiceName);
    }

    private static float[][] merge(List<float[][]> chunks) {
        if (chunks.isEmpty()) {
            return new float[][]{new float[0]};
        }
        int channels = chunks.get(0).length;
        int samples = chunks.stream().mapToInt(chunk -> chunk.length == 0 ? 0 : chunk[0].length).sum();
        float[][] merged = new float[channels][samples];
        int offset = 0;
        for (float[][] chunk : chunks) {
            if (chunk.length == 0) {
                continue;
            }
            int length = chunk[0].length;
            for (int channel = 0; channel < channels; channel++) {
                System.arraycopy(chunk[channel], 0, merged[channel], offset, length);
            }
            offset += length;
        }
        return merged;
    }

    private static String hash(float[][] channels) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] channel : channels) {
            for (float sample : channel) {
                buffer.clear();
                buffer.putInt(Float.floatToIntBits(sample));
                digest.update(buffer.array());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path resolveExistingPath(Path first, Path second) {
        return Files.exists(first) ? first.normalize() : second.normalize();
    }

    private static int smokeProcessors() {
        String value = System.getenv("TIANSHU_MOSS_PROCESSORS");
        try {
            return value == null || value.isBlank() ? 4 : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 4;
        }
    }

    private static final class ThreadedCodecExecution implements TtsCodecExecution, AutoCloseable {
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
