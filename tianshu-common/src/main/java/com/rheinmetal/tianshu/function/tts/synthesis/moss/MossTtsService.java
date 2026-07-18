package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class MossTtsService implements AutoCloseable {
    private final MossModelRuntime modelRuntime;
    private final MossFrameGenerator frameGenerator;
    private final MossAudioCodec audioCodec;

    public MossTtsService(IGameEnvironment env, HuggingFaceDownloader downloader, Path modelRootDir) {
        this(env, downloader, modelRootDir, InferenceResourcePolicy.systemDefault());
    }

    public MossTtsService(IGameEnvironment env, HuggingFaceDownloader downloader, Path modelRootDir, InferenceResourcePolicy resourcePolicy) {
        this.modelRuntime = new MossModelRuntime(env, downloader, modelRootDir, resourcePolicy);
        this.frameGenerator = new MossFrameGenerator(env, modelRuntime);
        this.audioCodec = new MossAudioCodec(env, modelRuntime);
    }

    public void init() throws Exception {
        modelRuntime.initialize();
    }

    public int[] encodeText(String text) {
        List<Integer> tokenIds = modelRuntime.tokenizer().encode(text == null ? "" : text);
        return tokenIds.stream().mapToInt(Integer::intValue).toArray();
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

    public List<List<Integer>> generateAudioFrames(RequestRows requestRows) throws Exception {
        return frameGenerator.generateAudioFrames(requestRows);
    }

    public List<List<Integer>> generateAudioFrames(
            RequestRows requestRows,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return frameGenerator.generateAudioFrames(requestRows, null, cancellationRequested);
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
        BooleanSupplier cancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        List<String> chunks = splitVoiceCloneText(text);
        if (chunks.isEmpty() || cancellation.getAsBoolean()) {
            return new float[][]{new float[0]};
        }
        if (chunks.size() == 1) {
            return synthesizeSingleChunk(chunks.get(0), promptAudioCodes, cancellation);
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
            float[][] chunkAudio = synthesizeSingleChunk(chunks.get(i), promptAudioCodes, cancellation);
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

    public interface StreamingAudioCallback {
        void onChunkAudio(float[][] audio, int chunkIndex, int totalChunks);
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
        List<String> chunks = splitVoiceCloneText(text);
        if (chunks.isEmpty()) {
            return;
        }
        int emittedAudioChunkIndex = 0;
        for (String chunk : chunks) {
            if (cancellation.getAsBoolean()) {
                return;
            }
            emittedAudioChunkIndex = synthesizeSingleChunkStreaming(
                    chunk,
                    promptAudioCodes,
                    callback,
                    emittedAudioChunkIndex,
                    cancellation
            );
        }
    }

    private int synthesizeSingleChunkStreaming(
            String text,
            List<List<Integer>> promptAudioCodes,
            StreamingAudioCallback callback,
            int firstAudioChunkIndex,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        int[] textTokenIds = encodeText(text);
        if (textTokenIds == null || textTokenIds.length == 0) {
            return firstAudioChunkIndex;
        }
        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        int[] chunkIndex = new int[]{firstAudioChunkIndex};
        try (MossAudioCodec.StreamingDecoder decoder = audioCodec.openStreamingDecoder()) {
            frameGenerator.generateAudioFrames(requestRows, (generatedFrames, stepIndex, frame) -> {
                if (cancellationRequested.getAsBoolean()) {
                    return;
                }
                DecodeResult decoded = decoder.acceptFrame(frame);
                if (decoded.audioLength > 0) {
                    callback.onChunkAudio(decoded.channels, chunkIndex[0]++, -1);
                }
            }, cancellationRequested);
            if (cancellationRequested.getAsBoolean()) {
                return chunkIndex[0];
            }
            DecodeResult tail = decoder.flush();
            if (tail.audioLength > 0) {
                callback.onChunkAudio(tail.channels, chunkIndex[0]++, -1);
            }
        }
        return chunkIndex[0];
    }

    private float[][] synthesizeSingleChunk(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        return synthesizeSingleChunk(text, promptAudioCodes, () -> false);
    }

    private float[][] synthesizeSingleChunk(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        return synthesizeSingleChunkDetailed(text, promptAudioCodes, cancellationRequested).channels;
    }

    public SynthesisResult synthesizeSingleChunkDetailed(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        return synthesizeSingleChunkDetailed(text, promptAudioCodes, () -> false);
    }

    public SynthesisResult synthesizeSingleChunkDetailed(
            String text,
            List<List<Integer>> promptAudioCodes,
            BooleanSupplier cancellationRequested
    ) throws Exception {
        long startNanos = System.nanoTime();
        int[] textTokenIds = encodeText(text);
        if (textTokenIds == null || textTokenIds.length == 0) {
            return new SynthesisResult(new float[][]{new float[0]}, 0, 0, 0, 0, 0);
        }
        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        long generateStartNanos = System.nanoTime();
        List<List<Integer>> generatedFrames = generateAudioFrames(requestRows, cancellationRequested);
        long generateMillis = elapsedMillis(generateStartNanos);
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

    private List<String> splitVoiceCloneText(String text) {
        text = text.trim();
        if (text.isEmpty()) return List.of();
        List<String> results = new ArrayList<>();
        List<String> bySentence = splitByPunctuation(text, "。！？!.;；");
        for (String sentence : bySentence) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;
            List<String> byClause = splitByPunctuation(sentence, "，,、：:");
            for (String clause : byClause) {
                clause = clause.trim();
                if (clause.isEmpty()) continue;
                results.add(clause);
            }
        }
        if (results.size() <= 1) return results;
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(results.get(0));
        for (int i = 1; i < results.size(); i++) {
            String piece = results.get(i);
            int estTokens = estimateTokens(current.toString()) + estimateTokens(piece);
            if (estTokens <= 75) {
                current.append(piece);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(piece);
            }
        }
        if (current.length() > 0) merged.add(current.toString());
        return merged;
    }

    private List<String> splitByPunctuation(String text, String punctuations) {
        List<String> parts = new ArrayList<>();
        int last = 0;
        for (int i = 0; i < text.length(); i++) {
            if (punctuations.indexOf(text.charAt(i)) >= 0) {
                String part = text.substring(last, i + 1).trim();
                if (!part.isEmpty()) parts.add(part);
                last = i + 1;
            }
        }
        if (last < text.length()) {
            String remaining = text.substring(last).trim();
            if (!remaining.isEmpty()) parts.add(remaining);
        }
        return parts;
    }

    private int estimateTokens(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) {
                count += 2;
            } else {
                count += 1;
            }
        }
        return Math.max(1, count / 2);
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
