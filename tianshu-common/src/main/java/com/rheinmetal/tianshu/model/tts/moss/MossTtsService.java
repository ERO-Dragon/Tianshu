package com.rheinmetal.tianshu.model.tts.moss;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.sentencepiece.SentencePieceProcessor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class MossTtsService implements AutoCloseable {

    private static final String TTS_REPO_ID = "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX";
    private static final String CODEC_REPO_ID = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX";
    private static final String REVISION = "main";
    private static final Gson GSON = new Gson();

    private final IGameEnvironment env;
    private final HuggingFaceDownloader downloader;
    private final Path modelRootDir;
    private final Path ttsDir;
    private final Path codecDir;
    private final Random random = new Random(1234L);

    private OrtEnvironment ortEnvironment;
    private SentencePieceProcessor sentencePieceProcessor;
    private JsonObject manifest;
    private JsonObject ttsMeta;
    private JsonObject codecMeta;
    private final Map<String, OrtSession> sessions = new HashMap<>();

    public MossTtsService(IGameEnvironment env, HuggingFaceDownloader downloader, Path modelRootDir) {
        this.env = env;
        this.downloader = downloader;
        this.modelRootDir = modelRootDir;
        this.ttsDir = modelRootDir;
        this.codecDir = modelRootDir;
    }

    public void init() throws Exception {
        if (!isModelDownloaded()) {
            downloadModels();
        }
        loadManifestAndMeta();
        initOrtEnvironment();
        initTokenizer();
        initSessions();
    }

    private boolean isModelDownloaded() {
        Path manifestPath = resolveManifestPath();
        return Files.isRegularFile(manifestPath);
    }

    private void downloadModels() throws Exception {
        env.info("开始下载 MOSS-TTS 模型文件");
        downloader.downloadModelFiles(TTS_REPO_ID, ttsDir, REVISION, true, 3);
        downloader.downloadModelFiles(CODEC_REPO_ID, codecDir, REVISION, true, 3);
        env.info("MOSS-TTS 模型文件下载完成");
    }

    private void loadManifestAndMeta() throws IOException {
        Path manifestPath = resolveManifestPath();
        manifest = GSON.fromJson(Files.readString(manifestPath), JsonObject.class);

        Path ttsMetaPath = resolveManifestRelativePath(manifest.getAsJsonObject("model_files").get("tts_meta").getAsString());
        Path codecMetaPath = resolveManifestRelativePath(manifest.getAsJsonObject("model_files").get("codec_meta").getAsString());

        ttsMeta = GSON.fromJson(Files.readString(ttsMetaPath), JsonObject.class);
        codecMeta = GSON.fromJson(Files.readString(codecMetaPath), JsonObject.class);
    }

    private void initOrtEnvironment() throws OrtException {
        ortEnvironment = OrtEnvironment.getEnvironment();
    }

    private void initTokenizer() throws IOException {
        String tokenizerRelativePath = manifest.getAsJsonObject("model_files").has("tokenizer_model")
                ? manifest.getAsJsonObject("model_files").get("tokenizer_model").getAsString()
                : "tokenizer.model";
        Path tokenizerPath = resolveManifestRelativePath(tokenizerRelativePath);
        sentencePieceProcessor = new SentencePieceProcessor(tokenizerPath);
    }

    private void initSessions() throws Exception {
        JsonObject ttsFiles = ttsMeta.getAsJsonObject("files");
        JsonObject codecFiles = codecMeta.getAsJsonObject("files");

        sessions.put("prefill", createSession(ttsDir.resolve(ttsFiles.get("prefill").getAsString())));
        sessions.put("decode", createSession(ttsDir.resolve(ttsFiles.get("decode_step").getAsString())));
        sessions.put("local_decoder", createSession(ttsDir.resolve(ttsFiles.get("local_decoder").getAsString())));

        if (ttsFiles.has("local_greedy_frame") && !ttsFiles.get("local_greedy_frame").isJsonNull()) {
            sessions.put("local_greedy_frame", createSession(ttsDir.resolve(ttsFiles.get("local_greedy_frame").getAsString())));
        }
        if (ttsFiles.has("local_fixed_sampled_frame") && !ttsFiles.get("local_fixed_sampled_frame").isJsonNull()) {
            sessions.put("local_fixed_sampled_frame", createSession(ttsDir.resolve(ttsFiles.get("local_fixed_sampled_frame").getAsString())));
        }
        if (ttsFiles.has("local_cached_step") && !ttsFiles.get("local_cached_step").isJsonNull()) {
            sessions.put("local_cached_step", createSession(ttsDir.resolve(ttsFiles.get("local_cached_step").getAsString())));
        }

        sessions.put("codec_encode", createSession(codecDir.resolve(codecFiles.get("encode").getAsString())));
        sessions.put("codec_decode", createSession(codecDir.resolve(codecFiles.get("decode_full").getAsString())));
    }

    private OrtSession createSession(Path modelPath) throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        //限定TTS在4个核上
        options.setIntraOpNumThreads(4);
        options.setInterOpNumThreads(1);
        return ortEnvironment.createSession(modelPath.toString(), options);
    }

    public int[] encodeText(String text) {
        List<Integer> tokenIds = sentencePieceProcessor.encode(text == null ? "" : text);
        return tokenIds.stream().mapToInt(Integer::intValue).toArray();
    }

    public List<List<Integer>> encodePromptAudioCodes(Path wavPath) throws Exception {
        if (wavPath == null || !Files.exists(wavPath)) {
            throw new IOException("参考音频文件不存在: " + wavPath);
        }

        float[][] pcmChannels = readWavAsFloatChannels(wavPath);
        float[][] mono = new float[1][];
        mono[0] = pcmChannels.length > 0 ? pcmChannels[0] : new float[0];

        int targetSampleRate = getSampleRate();
        mono = resampleIfNeeded(mono, (int) readWavSampleRate(wavPath), targetSampleRate);

        float[][][] waveform = new float[1][][];
        waveform[0] = mono;
        int waveformLength = mono[0].length;

        try (OnnxTensor waveformTensor = OnnxTensor.createTensor(ortEnvironment, waveform);
             OnnxTensor lengthsTensor = OnnxTensor.createTensor(ortEnvironment,
                     IntBuffer.wrap(new int[]{waveformLength}), new long[]{1})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("waveform", waveformTensor);
            inputs.put("input_lengths", lengthsTensor);

            OrtSession.Result result = sessions.get("codec_encode").run(inputs);
            int[][][] audioCodes = (int[][][]) result.get("audio_codes").get().getValue();
            int[] audioCodeLengths = (int[]) result.get("audio_code_lengths").get().getValue();
            int codeLength = audioCodeLengths[0];
            int numQuantizers = codecMeta.getAsJsonObject("codec_config").get("num_quantizers").getAsInt();

            List<List<Integer>> promptAudioCodes = new ArrayList<>();
            for (int frameIndex = 0; frameIndex < codeLength; frameIndex++) {
                List<Integer> frame = new ArrayList<>();
                for (int q = 0; q < numQuantizers; q++) {
                    frame.add(audioCodes[0][frameIndex][q]);
                }
                promptAudioCodes.add(frame);
            }
            return promptAudioCodes;
        }
    }

    private float[][] readWavAsFloatChannels(Path wavPath) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            AudioFormat format = ais.getFormat();
            byte[] bytes = ais.readAllBytes();
            int channels = format.getChannels();
            int sampleSize = format.getSampleSizeInBits() / 8;
            boolean bigEndian = format.isBigEndian();
            int totalSamples = bytes.length / (channels * sampleSize);

            float[][] channelData = new float[channels][totalSamples];
            for (int i = 0; i < totalSamples; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    int offset = (i * channels + ch) * sampleSize;
                    float sample;
                    if (sampleSize == 2) {
                        short s;
                        if (bigEndian) {
                            s = (short) ((bytes[offset] << 8) | (bytes[offset + 1] & 0xFF));
                        } else {
                            s = (short) ((bytes[offset + 1] << 8) | (bytes[offset] & 0xFF));
                        }
                        sample = s / 32768.0f;
                    } else if (sampleSize == 1) {
                        sample = ((bytes[offset] & 0xFF) - 128) / 128.0f;
                    } else {
                        sample = 0.0f;
                    }
                    channelData[ch][i] = Math.max(-1.0f, Math.min(1.0f, sample));
                }
            }
            return channelData;
        }
    }

    private float readWavSampleRate(Path wavPath) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            return ais.getFormat().getSampleRate();
        }
    }

    private float[][] resampleIfNeeded(float[][] mono, int sourceRate, int targetRate) {
        if (sourceRate == targetRate) return mono;
        double ratio = (double) targetRate / sourceRate;
        int newLength = (int) (mono[0].length * ratio);
        float[][] result = new float[1][newLength];
        for (int i = 0; i < newLength; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            if (srcIdx + 1 < mono[0].length) {
                float frac = (float) (srcPos - srcIdx);
                result[0][i] = mono[0][srcIdx] * (1 - frac) + mono[0][srcIdx + 1] * frac;
            } else if (srcIdx < mono[0].length) {
                result[0][i] = mono[0][srcIdx];
            }
        }
        return result;
    }

    public RequestRows buildVoiceCloneRequestRows(List<List<Integer>> promptAudioCodes, int[] textTokenIds) {
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");
        JsonObject promptTemplates = manifest.getAsJsonObject("prompt_templates");

        List<Integer> prefixTextTokenIds = new ArrayList<>();
        for (JsonElement element : promptTemplates.getAsJsonArray("user_prompt_prefix_token_ids")) {
            prefixTextTokenIds.add(element.getAsInt());
        }
        prefixTextTokenIds.add(ttsConfig.get("audio_start_token_id").getAsInt());

        List<Integer> suffixTextTokenIds = new ArrayList<>();
        suffixTextTokenIds.add(ttsConfig.get("audio_end_token_id").getAsInt());
        for (JsonElement element : promptTemplates.getAsJsonArray("user_prompt_after_reference_token_ids")) {
            suffixTextTokenIds.add(element.getAsInt());
        }
        for (int textTokenId : textTokenIds) {
            suffixTextTokenIds.add(textTokenId);
        }
        for (JsonElement element : promptTemplates.getAsJsonArray("assistant_prompt_prefix_token_ids")) {
            suffixTextTokenIds.add(element.getAsInt());
        }
        suffixTextTokenIds.add(ttsConfig.get("audio_start_token_id").getAsInt());

        List<int[]> rows = new ArrayList<>();
        rows.addAll(buildTextRows(prefixTextTokenIds));
        rows.addAll(buildAudioPrefixRows(promptAudioCodes != null ? promptAudioCodes : List.of(), null));
        rows.addAll(buildTextRows(suffixTextTokenIds));

        int[][] attentionMask = new int[1][rows.size()];
        Arrays.fill(attentionMask[0], 1);

        return new RequestRows(rows, attentionMask);
    }

    private List<int[]> buildTextRows(List<Integer> tokenIds) {
        List<int[]> rows = new ArrayList<>();
        int rowWidth = manifest.getAsJsonObject("tts_config").get("n_vq").getAsInt() + 1;
        int audioPad = manifest.getAsJsonObject("tts_config").get("audio_pad_token_id").getAsInt();

        for (int tokenId : tokenIds) {
            int[] row = new int[rowWidth];
            Arrays.fill(row, audioPad);
            row[0] = tokenId;
            rows.add(row);
        }
        return rows;
    }

    private List<int[]> buildAudioPrefixRows(List<List<Integer>> promptAudioCodes, Integer slotTokenId) {
        List<int[]> rows = new ArrayList<>();
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");
        int rowWidth = ttsConfig.get("n_vq").getAsInt() + 1;
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        int resolvedSlotTokenId = slotTokenId != null ? slotTokenId : ttsConfig.get("audio_user_slot_token_id").getAsInt();
        int nVq = ttsConfig.get("n_vq").getAsInt();

        for (List<Integer> codeRow : promptAudioCodes) {
            int[] row = new int[rowWidth];
            Arrays.fill(row, audioPad);
            row[0] = resolvedSlotTokenId;
            for (int i = 0; i < Math.min(codeRow.size(), nVq); i++) {
                row[i + 1] = codeRow.get(i);
            }
            rows.add(row);
        }
        return rows;
    }

    public List<List<Integer>> generateAudioFrames(RequestRows requestRows) throws Exception {
        JsonObject generationDefaults = manifest.getAsJsonObject("generation_defaults");
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");

        float[][][] inputIds = new float[1][requestRows.inputIds.size()][requestRows.inputIds.get(0).length];
        for (int i = 0; i < requestRows.inputIds.size(); i++) {
            for (int j = 0; j < requestRows.inputIds.get(i).length; j++) {
                inputIds[0][i][j] = requestRows.inputIds.get(i)[j];
            }
        }

        List<List<Integer>> generatedFrames = new ArrayList<>();
        List<List<Integer>> previousTokensByChannel = new ArrayList<>();
        List<Set<Integer>> previousTokenSetsByChannel = new ArrayList<>();
        int nVq = ttsConfig.get("n_vq").getAsInt();

        for (int i = 0; i < nVq; i++) {
            previousTokensByChannel.add(new ArrayList<>());
            previousTokenSetsByChannel.add(new HashSet<>());
        }

        try (OnnxTensor inputIdsTensor = createInt32Tensor3D(requestRows.inputIds);
             OnnxTensor attentionMaskTensor = createInt32Tensor2D(requestRows.attentionMask)) {

            Map<String, OnnxTensor> prefillInputs = new HashMap<>();
            prefillInputs.put("input_ids", inputIdsTensor);
            prefillInputs.put("attention_mask", attentionMaskTensor);

            OrtSession.Result prefillResult = sessions.get("prefill").run(prefillInputs);
            float[][] globalHidden = extractLastHidden((float[][][]) prefillResult.get("global_hidden").get().getValue());

            int maxNewFrames = generationDefaults.get("max_new_frames").getAsInt();
            for (int stepIndex = 0; stepIndex < maxNewFrames; stepIndex++) {
                List<Integer> frame = new ArrayList<>();

                if (sessions.containsKey("local_cached_step")) {
                    try {
                        frame = generateFrameWithCachedStep(globalHidden, previousTokensByChannel, previousTokenSetsByChannel);
                    } catch (Exception e) {
                        env.warn("MOSS local_cached_step 失败，回退到 local_decoder: " + e.getMessage());
                        frame = generateFrameWithLocalDecoder(globalHidden, previousTokensByChannel, previousTokenSetsByChannel);
                    }
                    if (frame.isEmpty()) {
                        break;
                    }
                } else {
                    frame = generateFrameWithLocalDecoder(globalHidden, previousTokensByChannel, previousTokenSetsByChannel);
                    if (frame.isEmpty()) {
                        break;
                    }
                }

                generatedFrames.add(frame);
                globalHidden = updateGlobalHidden(frame, globalHidden, ttsConfig);
            }
        }

        return generatedFrames;
    }

    private List<Integer> generateFrameWithCachedStep(
            float[][] globalHidden,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel
    ) throws Exception {
        JsonObject generationDefaults = manifest.getAsJsonObject("generation_defaults");
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");

        Map<String, OnnxTensor> localPast = createEmptyLocalCachedPast();
        int localPastValidLength = 0;

        CachedStepResult firstStep = runLocalCachedStep(globalHidden, 0, 0, 0, 0, localPastValidLength, localPast);
        localPastValidLength += 1;

        int nextTextToken = sampleAssistantTextToken(firstStep.textLogits, generationDefaults, ttsConfig);
        if (nextTextToken != ttsConfig.get("audio_assistant_slot_token_id").getAsInt()) {
            closeTensors(localPast);
            closeTensors(firstStep.nextLocalPast);
            return List.of();
        }

        closeTensors(localPast);
        localPast = firstStep.nextLocalPast;

        CachedStepResult secondStep = runLocalCachedStep(globalHidden, nextTextToken, 0, 0, 1, localPastValidLength, localPast);
        localPastValidLength += 1;
        closeTensors(localPast);
        localPast = secondStep.nextLocalPast;

        List<Integer> frame = new ArrayList<>();
        float[] firstChannelLogits = sliceAudioChannelLogits(secondStep.audioLogits, 0);
        int sampledToken = sampleAudioToken(firstChannelLogits, previousTokensByChannel.get(0), previousTokenSetsByChannel.get(0), generationDefaults);
        frame.add(sampledToken);
        previousTokensByChannel.get(0).add(sampledToken);
        previousTokenSetsByChannel.get(0).add(sampledToken);

        int previousToken = sampledToken;
        int nVq = ttsConfig.get("n_vq").getAsInt();
        for (int channelIndex = 1; channelIndex < nVq; channelIndex++) {
            CachedStepResult channelStep = runLocalCachedStep(globalHidden, 0, previousToken, channelIndex - 1, 2, localPastValidLength, localPast);
            localPastValidLength += 1;
            closeTensors(localPast);
            localPast = channelStep.nextLocalPast;

            float[] channelLogits = sliceAudioChannelLogits(channelStep.audioLogits, channelIndex);
            sampledToken = sampleAudioToken(channelLogits, previousTokensByChannel.get(channelIndex), previousTokenSetsByChannel.get(channelIndex), generationDefaults);
            frame.add(sampledToken);
            previousTokensByChannel.get(channelIndex).add(sampledToken);
            previousTokenSetsByChannel.get(channelIndex).add(sampledToken);
            previousToken = sampledToken;
        }

        closeTensors(localPast);
        return frame;
    }

    private List<Integer> generateFrameWithLocalDecoder(
            float[][] globalHidden,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel
    ) throws Exception {
        JsonObject generationDefaults = manifest.getAsJsonObject("generation_defaults");
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");

        LocalDecoderResult initial = runLocalDecoder(globalHidden, 0, List.of());
        int nextTextToken = sampleAssistantTextToken(initial.textLogits, generationDefaults, ttsConfig);
        if (nextTextToken != ttsConfig.get("audio_assistant_slot_token_id").getAsInt()) {
            return List.of();
        }

        List<Integer> frame = new ArrayList<>();
        int nVq = ttsConfig.get("n_vq").getAsInt();
        for (int channelIndex = 0; channelIndex < nVq; channelIndex++) {
            LocalDecoderResult step = runLocalDecoder(globalHidden, nextTextToken, frame);
            float[] channelLogits = sliceAudioChannelLogits(step.audioLogits, channelIndex);
            int sampledToken = sampleAudioToken(channelLogits, previousTokensByChannel.get(channelIndex), previousTokenSetsByChannel.get(channelIndex), generationDefaults);
            frame.add(sampledToken);
            previousTokensByChannel.get(channelIndex).add(sampledToken);
            previousTokenSetsByChannel.get(channelIndex).add(sampledToken);
        }
        return frame;
    }

    private float[][] updateGlobalHidden(List<Integer> frame, float[][] currentGlobalHidden, JsonObject ttsConfig) throws Exception {
        int rowWidth = ttsConfig.get("n_vq").getAsInt() + 1;
        int[][][] nextRow = new int[1][1][rowWidth];
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        for (int i = 0; i < rowWidth; i++) {
            nextRow[0][0][i] = audioPad;
        }
        nextRow[0][0][0] = ttsConfig.get("audio_assistant_slot_token_id").getAsInt();
        for (int i = 0; i < frame.size(); i++) {
            nextRow[0][0][i + 1] = frame.get(i);
        }

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnvironment, nextRow);
             OnnxTensor pastValidLengthsTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{1}), new long[]{1})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("past_valid_lengths", pastValidLengthsTensor);
            OrtSession.Result result = sessions.get("decode").run(inputs);
            return extractLastHidden((float[][][]) result.get("global_hidden").get().getValue());
        }
    }

    private LocalDecoderResult runLocalDecoder(float[][] globalHidden, int textTokenId, List<Integer> framePrefix) throws Exception {
        JsonObject ttsConfig = manifest.getAsJsonObject("tts_config");
        int nVq = ttsConfig.get("n_vq").getAsInt();
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        int[][] paddedPrefix = new int[1][nVq - 1];
        for (int i = 0; i < nVq - 1; i++) {
            paddedPrefix[0][i] = audioPad;
        }
        for (int i = 0; i < Math.min(framePrefix.size(), nVq - 1); i++) {
            paddedPrefix[0][i] = framePrefix.get(i);
        }

        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(ortEnvironment, globalHidden);
             OnnxTensor textTokenTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{textTokenId}), new long[]{1});
             OnnxTensor audioPrefixTensor = OnnxTensor.createTensor(ortEnvironment, paddedPrefix)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("text_token_id", textTokenTensor);
            inputs.put("audio_prefix_token_ids", audioPrefixTensor);

            OrtSession.Result result = sessions.get("local_decoder").run(inputs);
            float[] textLogits = ((float[][]) result.get("text_logits").get().getValue())[0];
            float[][] audioLogits = (float[][]) result.get("audio_logits").get().getValue();
            return new LocalDecoderResult(textLogits, audioLogits);
        }
    }

    private CachedStepResult runLocalCachedStep(
            float[][] globalHidden,
            int textTokenId,
            int audioTokenId,
            int channelIndex,
            int stepType,
            int pastValidLengths,
            Map<String, OnnxTensor> localPastByName
    ) throws Exception {
        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(ortEnvironment, globalHidden);
             OnnxTensor textTokenTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{textTokenId}), new long[]{1});
             OnnxTensor audioTokenTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{audioTokenId}), new long[]{1});
             OnnxTensor channelIndexTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{channelIndex}), new long[]{1});
             OnnxTensor stepTypeTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{stepType}), new long[]{1});
             OnnxTensor pastValidLengthsTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{pastValidLengths}), new long[]{1})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("text_token_id", textTokenTensor);
            inputs.put("audio_token_id", audioTokenTensor);
            inputs.put("channel_index", channelIndexTensor);
            inputs.put("step_type", stepTypeTensor);
            inputs.put("past_valid_lengths", pastValidLengthsTensor);
            inputs.putAll(localPastByName);

            OrtSession.Result result = sessions.get("local_cached_step").run(inputs);
            float[] textLogits = ((float[][]) result.get("text_logits").get().getValue())[0];
            float[][] audioLogits = (float[][]) result.get("audio_logits").get().getValue();

            Map<String, OnnxTensor> nextLocalPast = new HashMap<>();
            JsonArray outputNames = ttsMeta.getAsJsonObject("onnx").getAsJsonArray("local_cached_output_names");
            for (int i = 2; i < outputNames.size(); i++) {
                String outputName = outputNames.get(i).getAsString();
                String nextName = outputName.replace("local_present_", "local_past_");
                Object value = result.get(outputName).get().getValue();
                nextLocalPast.put(nextName, cloneTensor(value));
            }
            return new CachedStepResult(textLogits, audioLogits, nextLocalPast);
        }
    }

    private Map<String, OnnxTensor> createEmptyLocalCachedPast() throws Exception {
        JsonObject modelConfig = ttsMeta.getAsJsonObject("model_config");
        int localLayers = modelConfig.get("local_layers").getAsInt();
        int localHeads = modelConfig.get("local_heads").getAsInt();
        int localHeadDim = modelConfig.get("local_head_dim").getAsInt();

        if (localHeads <= 0 || localHeadDim <= 0) {
            throw new IllegalStateException("MOSS local cache 配置非法: localHeads=" + localHeads + ", localHeadDim=" + localHeadDim);
        }

        Map<String, OnnxTensor> result = new HashMap<>();
        for (int layerIndex = 0; layerIndex < localLayers; layerIndex++) {
            float[][][][] empty = new float[1][0][localHeads][localHeadDim];
            result.put("local_past_key_" + layerIndex, OnnxTensor.createTensor(ortEnvironment, empty));
            result.put("local_past_value_" + layerIndex, OnnxTensor.createTensor(ortEnvironment, empty));
        }
        return result;
    }

    public DecodeResult decodeFullAudio(List<List<Integer>> generatedFrames) throws Exception {
        if (generatedFrames.isEmpty()) {
            return new DecodeResult(new float[0][], 0);
        }

        int[][][] audioCodes = new int[1][generatedFrames.size()][generatedFrames.get(0).size()];
        for (int i = 0; i < generatedFrames.size(); i++) {
            for (int j = 0; j < generatedFrames.get(i).size(); j++) {
                audioCodes[0][i][j] = generatedFrames.get(i).get(j);
            }
        }

        try (OnnxTensor audioCodesTensor = OnnxTensor.createTensor(ortEnvironment, audioCodes);
             OnnxTensor lengthsTensor = OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(new int[]{generatedFrames.size()}), new long[]{1})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("audio_codes", audioCodesTensor);
            inputs.put("audio_code_lengths", lengthsTensor);

            OrtSession.Result result = sessions.get("codec_decode").run(inputs);
            float[][][] audio = (float[][][]) result.get("audio").get().getValue();
            int audioLength = ((int[]) result.get("audio_lengths").get().getValue())[0];
            return new DecodeResult(sliceChannelMajorAudio(audio, 0, audioLength), audioLength);
        }
    }

    public float[][] synthesizeToWaveform(String text, List<List<Integer>> promptAudioCodes) throws Exception {
        int[] textTokenIds = encodeText(text);
        
        // 3. 核心防御：如果 Tokenizer 转换出来是空的，也别往下走
        if (textTokenIds == null || textTokenIds.length == 0) {
            return new float[][]{new float[0]};
        }

        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        List<List<Integer>> generatedFrames = generateAudioFrames(requestRows);
        DecodeResult decodeResult = decodeFullAudio(generatedFrames);
        return decodeResult.channels;
    }

    public SynthesisResult synthesize(String text, List<List<Integer>> promptAudioCodes, Path outputWavPath) throws Exception {
        int[] textTokenIds = encodeText(text);
        RequestRows requestRows = buildVoiceCloneRequestRows(promptAudioCodes, textTokenIds);
        List<List<Integer>> generatedFrames = generateAudioFrames(requestRows);
        DecodeResult decodeResult = decodeFullAudio(generatedFrames);
        WavWriter.writeWaveFile(outputWavPath, decodeResult.channels, codecMeta.getAsJsonObject("codec_config").get("sample_rate").getAsInt());
        return new SynthesisResult(text, textTokenIds, generatedFrames, decodeResult.channels, outputWavPath);
    }

    public int getSampleRate() {
        if (codecMeta != null && codecMeta.has("codec_config")) {
            return codecMeta.getAsJsonObject("codec_config").get("sample_rate").getAsInt();
        }
        return 24000;
    }

    private int sampleAssistantTextToken(float[] textLogits, JsonObject generationDefaults, JsonObject ttsConfig) {
        int assistantSlotTokenId = ttsConfig.get("audio_assistant_slot_token_id").getAsInt();
        int audioEndTokenId = ttsConfig.get("audio_end_token_id").getAsInt();
        float[] candidateScores = new float[]{textLogits[assistantSlotTokenId], textLogits[audioEndTokenId]};
        int sampledIndex = MossSamplingUtils.sampleFromScores(
                candidateScores,
                generationDefaults.get("do_sample").getAsBoolean(),
                generationDefaults.get("text_temperature").getAsFloat(),
                Math.min(generationDefaults.get("text_top_k").getAsInt(), candidateScores.length),
                generationDefaults.get("text_top_p").getAsFloat(),
                random
        );
        return sampledIndex == 0 ? assistantSlotTokenId : audioEndTokenId;
    }

    private int sampleAudioToken(float[] audioLogits, List<Integer> previousTokenIds, Set<Integer> previousTokenSet, JsonObject generationDefaults) {
        float repetitionPenalty = generationDefaults.get("audio_repetition_penalty").getAsFloat();
        boolean doSample = generationDefaults.get("do_sample").getAsBoolean();
        if (!doSample) {
            return MossSamplingUtils.argmaxWithRepetitionPenalty(audioLogits, previousTokenSet, repetitionPenalty);
        }
        float[] penalizedScores = MossSamplingUtils.applyRepetitionPenalty(
                audioLogits,
                previousTokenIds.stream().mapToInt(Integer::intValue).toArray(),
                repetitionPenalty
        );
        return MossSamplingUtils.sampleFromScores(
                penalizedScores,
                true,
                generationDefaults.get("audio_temperature").getAsFloat(),
                generationDefaults.get("audio_top_k").getAsInt(),
                generationDefaults.get("audio_top_p").getAsFloat(),
                random
        );
    }

    private float[] sliceAudioChannelLogits(float[][] audioLogits, int channelIndex) {
        int totalLength = audioLogits[0].length;
        int nVq = manifest.getAsJsonObject("tts_config").get("n_vq").getAsInt();
        int perChannel = totalLength / nVq;
        int start = channelIndex * perChannel;
        return Arrays.copyOfRange(audioLogits[0], start, start + perChannel);
    }

    private float[][] extractLastHidden(float[][][] hiddenStates) {
        if (hiddenStates.length != 1) {
            throw new IllegalArgumentException("Unexpected global_hidden batch size: " + hiddenStates.length);
        }
        return new float[][]{hiddenStates[0][hiddenStates[0].length - 1]};
    }

    private float[][] sliceChannelMajorAudio(float[][][] audio, int startSample, int endSample) {
        int channels = audio[0].length;
        float[][] result = new float[channels][];
        for (int channelIndex = 0; channelIndex < channels; channelIndex++) {
            result[channelIndex] = Arrays.copyOfRange(audio[0][channelIndex], startSample, endSample);
        }
        return result;
    }

    private OnnxTensor createInt32Tensor3D(List<int[]> inputIds) throws OrtException {
        int[][][] array = new int[1][inputIds.size()][inputIds.get(0).length];
        for (int i = 0; i < inputIds.size(); i++) {
            array[0][i] = inputIds.get(i);
        }
        return OnnxTensor.createTensor(ortEnvironment, array);
    }

    private OnnxTensor createInt32Tensor2D(int[][] values) throws OrtException {
        return OnnxTensor.createTensor(ortEnvironment, values);
    }

    private OnnxTensor cloneTensor(Object value) throws OrtException {
        if (value instanceof float[][][][] tensor4d) {
            return OnnxTensor.createTensor(ortEnvironment, tensor4d);
        }
        if (value instanceof float[][][] tensor3d) {
            return OnnxTensor.createTensor(ortEnvironment, tensor3d);
        }
        if (value instanceof float[][] tensor2d) {
            return OnnxTensor.createTensor(ortEnvironment, tensor2d);
        }
        throw new IllegalArgumentException("Unsupported tensor clone type: " + value.getClass());
    }

    private void closeTensors(Map<String, OnnxTensor> tensors) {
        for (OnnxTensor tensor : tensors.values()) {
            try {
                tensor.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Path resolveManifestPath() {
        List<Path> candidates = List.of(
                modelRootDir.resolve("browser_poc_manifest.json"),
                modelRootDir.resolve("MOSS-TTS-Nano-100M-ONNX").resolve("browser_poc_manifest.json"),
                modelRootDir.resolve("MOSS-TTS-Nano-ONNX-CPU").resolve("browser_poc_manifest.json")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("browser_poc_manifest.json not found in model directory");
    }

    private Path resolveManifestRelativePath(String relativePath) {
        Path relative = Paths.get(relativePath);
        Path resolved = resolveManifestPath().getParent().resolve(relative).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path fileName = relative.getFileName();
        if (fileName != null) {
            Path found = searchFileInRoot(fileName.toString());
            if (found != null) {
                return found;
            }
        }
        return resolved;
    }

    private Path searchFileInRoot(String targetFileName) {
        try {
            return Files.walk(modelRootDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(targetFileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        if (ortEnvironment != null) {
            try {
                ortEnvironment.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static final class RequestRows {
        public final List<int[]> inputIds;
        public final int[][] attentionMask;

        public RequestRows(List<int[]> inputIds, int[][] attentionMask) {
            this.inputIds = inputIds;
            this.attentionMask = attentionMask;
        }
    }

    public static final class LocalDecoderResult {
        public final float[] textLogits;
        public final float[][] audioLogits;

        public LocalDecoderResult(float[] textLogits, float[][] audioLogits) {
            this.textLogits = textLogits;
            this.audioLogits = audioLogits;
        }
    }

    public static final class CachedStepResult {
        public final float[] textLogits;
        public final float[][] audioLogits;
        public final Map<String, OnnxTensor> nextLocalPast;

        public CachedStepResult(float[] textLogits, float[][] audioLogits, Map<String, OnnxTensor> nextLocalPast) {
            this.textLogits = textLogits;
            this.audioLogits = audioLogits;
            this.nextLocalPast = nextLocalPast;
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
        public final Path outputPath;

        public SynthesisResult(String text, int[] textTokenIds, List<List<Integer>> generatedFrames, float[][] waveformChannels, Path outputPath) {
            this.text = text;
            this.textTokenIds = textTokenIds;
            this.generatedFrames = generatedFrames;
            this.waveformChannels = waveformChannels;
            this.outputPath = outputPath;
        }
    }
}
