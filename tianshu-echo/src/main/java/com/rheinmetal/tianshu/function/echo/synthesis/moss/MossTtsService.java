package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsCodecExecution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.ThreadLocalRandom;

public class MossTtsService implements AutoCloseable {
    private static final int MAX_CONTEXT_TOKENS = 75;
    /**
     * Frames decoded per codec invocation.
     * <p>
     * Hard ceiling: one MOSS frame is 80 ms of audio, so a batch must stay well below
     * {@code TtsPreviewPlaybackPolicy.maxQueueMillis} (800 ms). A single chunk that alone exceeds the
     * playback queue capacity can never be accepted - the queue blocks until timeout and then rejects
     * it, silently dropping that audio. 4 frames = 320 ms leaves headroom in the 800 ms queue.
     */
    static final int DECODE_BATCH_FRAMES = 4;
    private static final LongSupplier RANDOM_SEED_SOURCE = () -> ThreadLocalRandom.current().nextLong();
    private final MossModelRuntime modelRuntime;
    private final MossFrameGenerator frameGenerator;
    private final MossAudioCodec audioCodec;
    private final MossTextChunker textChunker;
    private final LongSupplier synthesisSeedSource = RANDOM_SEED_SOURCE;
    private final MossStreamingDecodePipeline streamingDecodePipeline;

    public MossTtsService(
            IGameEnvironment env,
            Path modelRootDir,
            TtsCodecExecution codecExecution
    ) {
        this(env, modelRootDir, InferenceResourcePolicy.systemDefault(), codecExecution);
    }

    public MossTtsService(
            IGameEnvironment env,
            Path modelRootDir,
            InferenceResourcePolicy resourcePolicy,
            TtsCodecExecution codecExecution
    ) {
        this(
                env,
                modelRootDir,
                resourcePolicy,
                codecExecution,
                MossStreamingDecodeCadence.fixed(DECODE_BATCH_FRAMES)
        );
    }

    MossTtsService(
            IGameEnvironment env,
            Path modelRootDir,
            InferenceResourcePolicy resourcePolicy,
            TtsCodecExecution codecExecution,
            MossStreamingDecodeCadence cadence
    ) {
        this.modelRuntime = new MossModelRuntime(env, modelRootDir, resourcePolicy);
        this.frameGenerator = new MossFrameGenerator(env, modelRuntime);
        this.audioCodec = new MossAudioCodec(env, modelRuntime);
        this.textChunker = new MossTextChunker(MAX_CONTEXT_TOKENS, text -> encodeText(text).length);
        this.streamingDecodePipeline = new MossStreamingDecodePipeline(
                4,
                codecExecution,
                modelRuntime::sampleRate,
                cadence
        );
    }

    public void init() throws Exception {
        modelRuntime.initialize();
    }

    public int[] encodeText(String text) {
        List<Integer> tokenIds = modelRuntime.tokenizer().encode(text == null ? "" : text);
        return tokenIds.stream().mapToInt(Integer::intValue).toArray();
    }

    public void setGenerationSeed(long seed) {
        frameGenerator.setGenerationSeed(seed);
    }

    public int contextualSentenceLimit(List<String> sentences) {
        return textChunker.contextualSentenceLimit(sentences);
    }

    public List<List<Integer>> encodePromptAudioCodes(Path wavPath) throws Exception {
        return audioCodec.encodePromptAudioCodes(wavPath);
    }

    public List<String> listBuiltinVoiceNames() {
        JsonObject manifest = modelRuntime.manifest();
        JsonArray voices = manifest.has("builtin_voices")
                ? manifest.getAsJsonArray("builtin_voices")
                : new JsonArray();
        List<String> names = new ArrayList<>(voices.size());
        for (JsonElement element : voices) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject voice = element.getAsJsonObject();
            if (voice.has("voice") && !voice.get("voice").isJsonNull()) {
                names.add(voice.get("voice").getAsString());
            }
        }
        return names;
    }

    public List<List<Integer>> resolveBuiltinVoicePromptAudioCodes(String voiceName) {
        JsonObject manifest = modelRuntime.manifest();
        if (!manifest.has("builtin_voices")) {
            throw new IllegalStateException("MOSS manifest has no built-in voices");
        }
        String requestedVoice = voiceName == null || voiceName.isBlank() ? "Junhao" : voiceName.trim();
        for (JsonElement element : manifest.getAsJsonArray("builtin_voices")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject voice = element.getAsJsonObject();
            String currentName = voice.has("voice") && !voice.get("voice").isJsonNull()
                    ? voice.get("voice").getAsString()
                    : "";
            if (requestedVoice.equalsIgnoreCase(currentName)) {
                return parsePromptAudioCodes(voice.getAsJsonArray("prompt_audio_codes"));
            }
        }
        throw new IllegalArgumentException(
                "MOSS_BUILTIN_VOICE_NOT_FOUND requested=" + requestedVoice
                        + " available=" + listBuiltinVoiceNames()
        );
    }

    private List<List<Integer>> parsePromptAudioCodes(JsonArray rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("MOSS_BUILTIN_VOICE_PROMPT_CODES_MISSING");
        }
        List<List<Integer>> result = new ArrayList<>(rows.size());
        for (JsonElement rowElement : rows) {
            if (!rowElement.isJsonArray()) {
                continue;
            }
            JsonArray rowArray = rowElement.getAsJsonArray();
            List<Integer> row = new ArrayList<>(rowArray.size());
            for (JsonElement tokenElement : rowArray) {
                row.add(tokenElement.getAsInt());
            }
            result.add(row);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("MOSS_BUILTIN_VOICE_PROMPT_CODES_EMPTY");
        }
        return result;
    }

    public RequestRows buildVoiceCloneRequestRows(List<List<Integer>> promptAudioCodes, int[] textTokenIds) {
        return frameGenerator.buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
    }

    private MossFrameGenerationResult generateAudioFrames(
            RequestRows requestRows,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return frameGenerator.generateAudioFrames(requestRows, null, cancellationRequested, false);
    }

    private MossFrameGenerationResult generateAudioFramesWithRepeatGuard(
            RequestRows requestRows,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return frameGenerator.generateAudioFrames(requestRows, null, cancellationRequested, true);
    }
    public DecodeResult decodeFullAudio(List<List<Integer>> generatedFrames) throws Exception {
        return audioCodec.decodeFullAudio(generatedFrames);
    }

    public DecodeResult decodeFullAudioSafe(List<List<Integer>> generatedFrames) throws Exception {
        return audioCodec.decodeFullAudioSafe(generatedFrames);
    }
    public float[][] synthesizeToWaveform(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        return synthesizeToWaveform(text, promptAudioCodes, () -> false);
    }

    public float[][] synthesizeToWaveform(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return synthesizeToWaveform(text, promptAudioCodes, cancellationRequested, false);
    }

    private float[][] synthesizeToWaveform(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested,
            boolean detectRepeatedFrames
    ) throws Exception {
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        List<String> chunks = textChunker.split(text);
        if (chunks.isEmpty() || cancellation.getAsBoolean()) {
            return new float[][]{new float[0]};
        }
        if (chunks.size() == 1) {
            return synthesizeSingleChunk(chunks.get(0), promptAudioCodes, cancellation, detectRepeatedFrames);
        }
        int sampleRate = getSampleRate();
        int channels = audioCodec.channels();
        float interChunkPauseShort = 0.24f;
        int pauseSamplesShort = (int) (sampleRate * interChunkPauseShort);
        float interChunkPauseLong = 0.40f;
        int pauseSamplesLong = (int) (sampleRate * interChunkPauseLong);

        List<float[][]> chunkAudios = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (cancellation.getAsBoolean()) {
                break;
            }
            float[][] chunkAudio = synthesizeSingleChunk(chunks.get(i), promptAudioCodes, cancellation, detectRepeatedFrames);
            if (chunkAudio == null || chunkAudio.length == 0 || chunkAudio[0].length == 0) {
                continue;
            }
            chunkAudios.add(chunkAudio);
            if (i < chunks.size() - 1) {
                int pauseSamples = isSentenceEnding(chunks.get(i)) ? pauseSamplesLong : pauseSamplesShort;
                float[][] silence = new float[channels][pauseSamples];
                chunkAudios.add(silence);
            }
        }
        if (chunkAudios.isEmpty()) {
            return new float[][]{new float[0]};
        }
        int totalLength = 0;
        for (float[][] ca : chunkAudios) {
            totalLength += ca[0].length;
        }
        float[][] merged = new float[channels][totalLength];
        int offset = 0;
        for (float[][] ca : chunkAudios) {
            for (int ch = 0; ch < channels; ch++) {
                System.arraycopy(ca[Math.min(ch, ca.length - 1)], 0, merged[ch], offset, ca[Math.min(ch, ca.length - 1)].length);
            }
            offset += ca[0].length;
        }
        return merged;
    }

    public float[][] synthesizeToWaveformResilient(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        MossOfflineSynthesisRetry retry = new MossOfflineSynthesisRetry(synthesisSeedSource);
        return retry.execute(cancellation, seed -> {
            setGenerationSeed(seed);
            return synthesizeToWaveform(text, promptAudioCodes, cancellation, true);
        });
    }

    public interface StreamingAudioCallback {
        void onChunkAudio(float[][] audio, int chunkIndex, int totalChunks);

        default void onChunkAudio(
                float[][] audio,
                int chunkIndex,
                int totalChunks,
                StreamingChunkMetadata metadata
        ) {
            onChunkAudio(audio, chunkIndex, totalChunks);
        }
    }

    public record StreamingChunkMetadata(
            long pcmDurationMillis,
            long generationElapsedMillis,
            long interChunkIntervalMillis
    ) {
        public StreamingChunkMetadata {
            pcmDurationMillis = Math.max(0L, pcmDurationMillis);
            generationElapsedMillis = Math.max(0L, generationElapsedMillis);
            interChunkIntervalMillis = Math.max(0L, interChunkIntervalMillis);
        }
    }

    public void synthesizeStreaming(String text, List<List<Integer>> promptAudioCodes, StreamingAudioCallback callback) throws Exception {
        synthesizeStreaming(text, promptAudioCodes, callback, () -> false);
    }

    public void synthesizeStreaming(
            String text,
            List<List<Integer>> promptAudioCodes,
            StreamingAudioCallback callback,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        List<String> chunks = textChunker.split(text);
        if (chunks.isEmpty()) {
            return;
        }
        int emittedAudioChunkIndex = 0;
        long startedNanos = System.nanoTime();
        long[] previousChunkNanos = {0L};
        for (String chunk : chunks) {
            if (cancellation.getAsBoolean()) {
                return;
            }
            emittedAudioChunkIndex = synthesizeSingleChunkStreaming(
                    chunk,
                    promptAudioCodes,
                    callback,
                    emittedAudioChunkIndex,
                    -1,
                    startedNanos,
                    previousChunkNanos,
                    cancellation
            );
        }
    }

    private int synthesizeSingleChunkStreaming(
            String text,
            List<List<Integer>> promptAudioCodes,
            StreamingAudioCallback callback,
            int firstAudioChunkIndex,
            int totalChunks,
            long startedNanos,
            long[] previousChunkNanos,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        int[] textTokenIds = encodeText(text);
        if (textTokenIds == null || textTokenIds.length == 0) {
            return firstAudioChunkIndex;
        }
        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        int[] chunkIndex = new int[]{firstAudioChunkIndex};
        MossFrameGenerationResult generation = streamingDecodePipeline.run(
                (frameConsumer, pipelineCancellation) -> frameGenerator.generateAudioFrames(
                        requestRows,
                        frameConsumer,
                        pipelineCancellation
                ),
                () -> new StreamingDecoderAdapter(audioCodec.openStreamingDecoder()),
                audio -> {
                    long nowNanos = System.nanoTime();
                    long previousNanos = previousChunkNanos[0];
                    previousChunkNanos[0] = nowNanos;
                    long pcmDurationMillis = pcmDurationMillis(audio);
                    callback.onChunkAudio(
                            audio,
                            chunkIndex[0]++,
                            totalChunks,
                            new StreamingChunkMetadata(
                                    pcmDurationMillis,
                                    elapsedMillis(startedNanos),
                                    previousNanos == 0L ? 0L : elapsedMillis(previousNanos)
                            )
                    );
                },
                cancellationRequested
        );
        if (cancellationRequested.getAsBoolean() || generation.cancelled()) {
            return chunkIndex[0];
        }
        generation.requireNaturalEnd(textTokenIds.length);
        return chunkIndex[0];
    }

    private long pcmDurationMillis(float[][] audio) {
        return pcmDurationMillis(audio, getSampleRate());
    }

    static long pcmDurationMillis(float[][] audio, int sampleRate) {
        if (audio == null || audio.length == 0 || audio[0] == null || audio[0].length == 0) {
            return 0L;
        }
        return audio[0].length * 1_000L / Math.max(1, sampleRate);
    }

    private static final class StreamingDecoderAdapter implements MossStreamingDecodePipeline.Decoder {
        private final MossAudioCodec.StreamingDecoder decoder;

        private StreamingDecoderAdapter(MossAudioCodec.StreamingDecoder decoder) {
            this.decoder = decoder;
        }

        @Override
        public List<float[][]> decode(List<List<Integer>> frames, boolean finalBatch) throws Exception {
            List<float[][]> audio = new ArrayList<>();
            DecodeResult decoded = decoder.decodeFrames(frames);
            if (decoded.audioLength > 0) {
                audio.add(decoded.channels);
            }
            return audio;
        }

        @Override
        public void close() {
            decoder.close();
        }
    }

    private float[][] synthesizeSingleChunk(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        return synthesizeSingleChunk(text, promptAudioCodes, () -> false);
    }

    private float[][] synthesizeSingleChunk(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return synthesizeSingleChunk(text, promptAudioCodes, cancellationRequested, false);
    }

    private float[][] synthesizeSingleChunk(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested,
            boolean detectRepeatedFrames
    ) throws Exception {
        return synthesizeSingleChunkDetailed(text, promptAudioCodes, cancellationRequested, detectRepeatedFrames).channels;
    }

    public SynthesisResult synthesizeSingleChunkDetailed(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        return synthesizeSingleChunkDetailed(text, promptAudioCodes, () -> false);
    }

    public SynthesisResult synthesizeSingleChunkDetailed(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return synthesizeSingleChunkDetailed(text, promptAudioCodes, cancellationRequested, false);
    }

    private SynthesisResult synthesizeSingleChunkDetailed(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested,
            boolean detectRepeatedFrames
    ) throws Exception {
        long startNanos = System.nanoTime();
        int[] textTokenIds = encodeText(text);
        if (textTokenIds == null || textTokenIds.length == 0) {
            return new SynthesisResult(new float[][]{new float[0]}, 0, 0, 0, 0, 0);
        }
        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        long generateStartNanos = System.nanoTime();
        MossFrameGenerationResult generation = detectRepeatedFrames
                ? generateAudioFramesWithRepeatGuard(requestRows, cancellationRequested)
                : generateAudioFrames(requestRows, cancellationRequested);
        long generateMillis = elapsedMillis(generateStartNanos);
        if (generation.cancelled()) {
            return new SynthesisResult(new float[][]{new float[0]}, textTokenIds.length, generation.generatedFrameCount(), elapsedMillis(startNanos), generateMillis, 0);
        }
        List<List<Integer>> generatedFrames = generation.requireNaturalEnd(textTokenIds.length);
        if (generatedFrames.isEmpty()) {
            return new SynthesisResult(new float[][]{new float[0]}, textTokenIds.length, 0, elapsedMillis(startNanos), generateMillis, 0);
        }
        long decodeStartNanos = System.nanoTime();
        DecodeResult decodeResult = decodeFullAudioSafe(generatedFrames);
        long decodeMillis = elapsedMillis(decodeStartNanos);
        return new SynthesisResult(
                decodeResult.channels,
                textTokenIds.length,
                generatedFrames.size(),
                elapsedMillis(startNanos),
                generateMillis,
                decodeMillis
        );
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private boolean isSentenceEnding(String text) {
        if (text.isEmpty()) return false;
        char last = text.charAt(text.length() - 1);
        return "。！？!?.;；".indexOf(last) >= 0;
    }

    public SynthesisResult synthesize(String text, List<List<Integer>> promptAudioCodes, Path outputWavPath) throws Exception {
        float[][] waveform = synthesizeToWaveform(text, promptAudioCodes);
        WavWriter.writeWaveFile(outputWavPath, waveform, getSampleRate());
        int[] textTokenIds = encodeText(text);
        List<List<Integer>> generatedFrames = List.of();
        return new SynthesisResult(text, textTokenIds != null ? textTokenIds : new int[0], generatedFrames, waveform, outputWavPath);
    }

    public int getSampleRate() {
        return modelRuntime.sampleRate();
    }

    @Override
    public void close() {
        modelRuntime.close();
    }

    public static final class RequestRows {
        public final List<int[]> inputIds;
        public final int[][] attentionMask;

        public RequestRows(List<int[]> inputIds, int[][] attentionMask) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
        }
    }

    public static final class DecodeResult {
        public final float[][] channels;
        public final int audioLength;

        public DecodeResult(float[][] channels, int audioLength) {
            this.channels = channels;
            this.audioLength = audioLength;
        }
    }

    public static final class SynthesisResult {
        public final String text;
        public final int[] textTokenIds;
        public final List<List<Integer>> generatedFrames;
        public final float[][] waveformChannels;
        public final float[][] channels;
        public final Path outputPath;
        public final int textTokenCount;
        public final int generatedFrameCount;
        public final long totalMillis;
        public final long generateMillis;
        public final long decodeMillis;

        public SynthesisResult(String text, int[] textTokenIds, List<List<Integer>> generatedFrames, float[][] waveformChannels, Path outputPath) {
            this(
                    text,
                    textTokenIds,
                    generatedFrames,
                    waveformChannels,
                    outputPath,
                    0,
                    0,
                    0
            );
        }

        public SynthesisResult(float[][] channels, int textTokenCount, int generatedFrameCount, long totalMillis, long generateMillis, long decodeMillis) {
            this(
                    "",
                    new int[textTokenCount],
                    List.of(),
                    channels,
                    null,
                    totalMillis,
                    generateMillis,
                    decodeMillis,
                    textTokenCount,
                    generatedFrameCount
            );
        }

        public SynthesisResult(
                String text,
                int[] textTokenIds,
                List<List<Integer>> generatedFrames,
                float[][] waveformChannels,
                Path outputPath,
                long totalMillis,
                long generateMillis,
                long decodeMillis
        ) {
            this(
                    text,
                    textTokenIds,
                    generatedFrames,
                    waveformChannels,
                    outputPath,
                    totalMillis,
                    generateMillis,
                    decodeMillis,
                    textTokenIds == null ? 0 : textTokenIds.length,
                    generatedFrames == null ? 0 : generatedFrames.size()
            );
        }

        private SynthesisResult(
                String text,
                int[] textTokenIds,
                List<List<Integer>> generatedFrames,
                float[][] waveformChannels,
                Path outputPath,
                long totalMillis,
                long generateMillis,
                long decodeMillis,
                int textTokenCount,
                int generatedFrameCount
        ) {
            this.text = text;
            this.textTokenIds = textTokenIds;
            this.generatedFrames = generatedFrames;
            this.waveformChannels = waveformChannels;
            this.channels = waveformChannels;
            this.outputPath = outputPath;
            this.textTokenCount = textTokenCount;
            this.generatedFrameCount = generatedFrameCount;
            this.totalMillis = totalMillis;
            this.generateMillis = generateMillis;
            this.decodeMillis = decodeMillis;
        }
    }
}
