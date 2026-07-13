package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Runs MOSS autoregressive frame generation and owns its tensor-cache transitions. */
final class MossFrameGenerator {
    static final String SAMPLE_MODE_GREEDY = "greedy";
    static final String SAMPLE_MODE_FIXED = "fixed";
    static final String SAMPLE_MODE_FULL = "full";

    private final IGameEnvironment env;
    private final MossModelRuntime modelRuntime;
    private final Random random = new Random(1234L);

    MossFrameGenerator(IGameEnvironment env, MossModelRuntime modelRuntime) {
        this.env = env;
        this.modelRuntime = modelRuntime;
    }

    MossTtsService.RequestRows buildVoiceCloneRequestRows(List<List<Integer>> promptAudioCodes, int[] textTokenIds) {
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
        JsonObject promptTemplates = modelRuntime.manifest().getAsJsonObject("prompt_templates");

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
        return new MossTtsService.RequestRows(rows, attentionMask);
    }

    List<List<Integer>> generateAudioFrames(MossTtsService.RequestRows requestRows) throws Exception {
        return generateAudioFrames(requestRows, null);
    }

    List<List<Integer>> generateAudioFrames(
            MossTtsService.RequestRows requestRows,
            FrameCallback frameCallback
    ) throws Exception {
        JsonObject generationDefaults = generationDefaults();
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
        JsonObject ttsOnnx = modelRuntime.ttsMeta().getAsJsonObject("onnx");

        List<List<Integer>> generatedFrames = new ArrayList<>();
        List<List<Integer>> previousTokensByChannel = new ArrayList<>();
        List<Set<Integer>> previousTokenSetsByChannel = new ArrayList<>();
        int nVq = ttsConfig.get("n_vq").getAsInt();
        int rowWidth = nVq + 1;

        for (int index = 0; index < nVq; index++) {
            previousTokensByChannel.add(new ArrayList<>());
            previousTokenSetsByChannel.add(new HashSet<>());
        }

        try (OnnxTensor inputIdsTensor = createInt32Tensor3D(requestRows.inputIds);
             OnnxTensor attentionMaskTensor = createInt32Tensor2D(requestRows.attentionMask);
             MossTensorState pastState = new MossTensorState()) {
            Map<String, OnnxTensor> prefillInputs = new HashMap<>();
            prefillInputs.put("input_ids", inputIdsTensor);
            prefillInputs.put("attention_mask", attentionMaskTensor);

            OrtSession.Result prefillResult = modelRuntime.requireSession("prefill").run(prefillInputs);
            OnnxValue globalHiddenValue = prefillResult.get("global_hidden").orElseThrow();
            float[][] globalHidden;
            try {
                globalHidden = extractLastHidden((float[][][]) globalHiddenValue.getValue());
                pastState.replaceWith(MossTensorState.takeOutputs(
                        prefillResult,
                        ttsOnnx.getAsJsonArray("prefill_output_names"),
                        "present_",
                        "past_"
                ));
            } finally {
                closeValue(globalHiddenValue);
            }

            int pastValidLength = sumAttentionMask(requestRows.attentionMask[0]);
            int maxNewFrames = generationDefaults.get("max_new_frames").getAsInt();
            for (int stepIndex = 0; stepIndex < maxNewFrames; stepIndex++) {
                List<Integer> frame = generateFrame(
                        globalHidden,
                        previousTokensByChannel,
                        previousTokenSetsByChannel,
                        generationDefaults
                );
                if (frame.isEmpty()) {
                    break;
                }

                generatedFrames.add(frame);
                if (frameCallback != null) {
                    frameCallback.onFrame(generatedFrames, stepIndex, frame);
                }
                DecodeStepResult decodeStep = runDecodeStep(
                        frame,
                        pastValidLength,
                        pastState.tensors(),
                        rowWidth,
                        ttsConfig,
                        ttsOnnx
                );
                globalHidden = decodeStep.globalHidden;
                pastValidLength += 1;
                pastState.replaceWith(decodeStep.nextPastByName);
            }
        }
        return generatedFrames;
    }

    private List<Integer> generateFrame(
            float[][] globalHidden,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel,
            JsonObject generationDefaults
    ) throws Exception {
        if (modelRuntime.hasSession("local_greedy_frame") && !generationDefaults.get("do_sample").getAsBoolean()) {
            GreedyFrameResult greedyFrame = runLocalGreedyFrame(
                    globalHidden,
                    previousTokenSetsByChannel,
                    generationDefaults
            );
            return recordFrame(greedyFrame, previousTokensByChannel, previousTokenSetsByChannel);
        }
        if (modelRuntime.hasSession("local_fixed_sampled_frame")
                && SAMPLE_MODE_FIXED.equalsIgnoreCase(generationDefaults.get("sample_mode").getAsString())) {
            try {
                GreedyFrameResult fixedFrame = runLocalFixedSampledFrame(globalHidden, previousTokenSetsByChannel);
                return recordFrame(fixedFrame, previousTokensByChannel, previousTokenSetsByChannel);
            } catch (Exception failure) {
                env.warn("MOSS local_fixed_sampled_frame failed; using fallback: " + failure.getMessage());
            }
        }
        if (modelRuntime.hasSession("local_cached_step")) {
            return generateFrameWithCachedStep(globalHidden, previousTokensByChannel, previousTokenSetsByChannel);
        }
        return generateFrameWithLocalDecoder(globalHidden, previousTokensByChannel, previousTokenSetsByChannel);
    }

    private List<Integer> recordFrame(
            GreedyFrameResult result,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel
    ) {
        if (!result.shouldContinue) {
            return List.of();
        }
        for (int channelIndex = 0; channelIndex < result.frame.size(); channelIndex++) {
            int sampledToken = result.frame.get(channelIndex);
            previousTokensByChannel.get(channelIndex).add(sampledToken);
            previousTokenSetsByChannel.get(channelIndex).add(sampledToken);
        }
        return result.frame;
    }

    private List<int[]> buildTextRows(List<Integer> tokenIds) {
        List<int[]> rows = new ArrayList<>();
        int rowWidth = modelRuntime.manifest().getAsJsonObject("tts_config").get("n_vq").getAsInt() + 1;
        int audioPad = modelRuntime.manifest().getAsJsonObject("tts_config").get("audio_pad_token_id").getAsInt();
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
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
        int rowWidth = ttsConfig.get("n_vq").getAsInt() + 1;
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        int resolvedSlotTokenId = slotTokenId != null
                ? slotTokenId
                : ttsConfig.get("audio_user_slot_token_id").getAsInt();
        int nVq = ttsConfig.get("n_vq").getAsInt();
        for (List<Integer> codeRow : promptAudioCodes) {
            int[] row = new int[rowWidth];
            Arrays.fill(row, audioPad);
            row[0] = resolvedSlotTokenId;
            for (int index = 0; index < Math.min(codeRow.size(), nVq); index++) {
                row[index + 1] = codeRow.get(index);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<Integer> generateFrameWithCachedStep(
            float[][] globalHidden,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel
    ) throws Exception {
        JsonObject generationDefaults = generationDefaults();
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
        Map<String, OnnxTensor> localPast = createEmptyLocalCachedPast();
        int localPastValidLength = 0;
        try {
            CachedStepResult firstStep = runLocalCachedStep(
                    globalHidden, 0, 0, 0, 0, localPastValidLength, localPast
            );
            localPastValidLength += 1;
            int nextTextToken = sampleAssistantTextToken(firstStep.textLogits, generationDefaults, ttsConfig);
            if (nextTextToken != ttsConfig.get("audio_assistant_slot_token_id").getAsInt()) {
                closeTensors(firstStep.nextLocalPast);
                return List.of();
            }

            closeTensors(localPast);
            localPast = firstStep.nextLocalPast;
            CachedStepResult secondStep = runLocalCachedStep(
                    globalHidden, nextTextToken, 0, 0, 1, localPastValidLength, localPast
            );
            localPastValidLength += 1;
            closeTensors(localPast);
            localPast = secondStep.nextLocalPast;

            List<Integer> frame = new ArrayList<>();
            float[] firstChannelLogits = sliceAudioChannelLogits(secondStep.audioLogits, 0);
            int sampledToken = sampleAudioToken(
                    firstChannelLogits,
                    previousTokensByChannel.get(0),
                    previousTokenSetsByChannel.get(0),
                    generationDefaults
            );
            frame.add(sampledToken);
            previousTokensByChannel.get(0).add(sampledToken);
            previousTokenSetsByChannel.get(0).add(sampledToken);

            int previousToken = sampledToken;
            int nVq = ttsConfig.get("n_vq").getAsInt();
            for (int channelIndex = 1; channelIndex < nVq; channelIndex++) {
                CachedStepResult channelStep = runLocalCachedStep(
                        globalHidden,
                        0,
                        previousToken,
                        channelIndex - 1,
                        2,
                        localPastValidLength,
                        localPast
                );
                localPastValidLength += 1;
                closeTensors(localPast);
                localPast = channelStep.nextLocalPast;

                float[] channelLogits = sliceAudioChannelLogits(channelStep.audioLogits, channelIndex);
                sampledToken = sampleAudioToken(
                        channelLogits,
                        previousTokensByChannel.get(channelIndex),
                        previousTokenSetsByChannel.get(channelIndex),
                        generationDefaults
                );
                frame.add(sampledToken);
                previousTokensByChannel.get(channelIndex).add(sampledToken);
                previousTokenSetsByChannel.get(channelIndex).add(sampledToken);
                previousToken = sampledToken;
            }
            return frame;
        } finally {
            closeTensors(localPast);
        }
    }

    private List<Integer> generateFrameWithLocalDecoder(
            float[][] globalHidden,
            List<List<Integer>> previousTokensByChannel,
            List<Set<Integer>> previousTokenSetsByChannel
    ) throws Exception {
        JsonObject generationDefaults = generationDefaults();
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
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
            int sampledToken = sampleAudioToken(
                    channelLogits,
                    previousTokensByChannel.get(channelIndex),
                    previousTokenSetsByChannel.get(channelIndex),
                    generationDefaults
            );
            frame.add(sampledToken);
            previousTokensByChannel.get(channelIndex).add(sampledToken);
            previousTokenSetsByChannel.get(channelIndex).add(sampledToken);
        }
        return frame;
    }

    private DecodeStepResult runDecodeStep(
            List<Integer> frame,
            int pastValidLength,
            Map<String, OnnxTensor> pastByName,
            int rowWidth,
            JsonObject ttsConfig,
            JsonObject ttsOnnx
    ) throws Exception {
        int[][][] nextRow = new int[1][1][rowWidth];
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        Arrays.fill(nextRow[0][0], audioPad);
        nextRow[0][0][0] = ttsConfig.get("audio_assistant_slot_token_id").getAsInt();
        for (int index = 0; index < frame.size(); index++) {
            nextRow[0][0][index + 1] = frame.get(index);
        }

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(modelRuntime.environment(), nextRow);
             OnnxTensor pastValidLengthsTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(),
                     IntBuffer.wrap(new int[]{pastValidLength}),
                     new long[]{1}
             )) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("past_valid_lengths", pastValidLengthsTensor);
            for (JsonElement inputNameElement : ttsOnnx.getAsJsonArray("decode_input_names")) {
                String inputName = inputNameElement.getAsString();
                if (!"input_ids".equals(inputName) && !"past_valid_lengths".equals(inputName)) {
                    inputs.put(inputName, pastByName.get(inputName));
                }
            }

            OrtSession.Result result = modelRuntime.requireSession("decode").run(inputs);
            OnnxValue globalHiddenValue = result.get("global_hidden").orElseThrow();
            try {
                float[][] globalHidden = extractLastHidden((float[][][]) globalHiddenValue.getValue());
                Map<String, OnnxTensor> nextPastByName = MossTensorState.takeOutputs(
                        result,
                        ttsOnnx.getAsJsonArray("decode_output_names"),
                        "present_",
                        "past_"
                );
                return new DecodeStepResult(globalHidden, nextPastByName);
            } finally {
                closeValue(globalHiddenValue);
            }
        }
    }

    private GreedyFrameResult runLocalGreedyFrame(
            float[][] globalHidden,
            List<Set<Integer>> previousTokenSetsByChannel,
            JsonObject generationDefaults
    ) throws Exception {
        int[][][] repetitionSeenMask = repetitionSeenMask(previousTokenSetsByChannel);
        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(modelRuntime.environment(), globalHidden);
             OnnxTensor repetitionSeenMaskTensor = OnnxTensor.createTensor(modelRuntime.environment(), repetitionSeenMask);
             OnnxTensor repetitionPenaltyTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(),
                     new float[][]{{generationDefaults.get("audio_repetition_penalty").getAsFloat()}}
             )) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("repetition_seen_mask", repetitionSeenMaskTensor);
            inputs.put("repetition_penalty", repetitionPenaltyTensor);
            try (OrtSession.Result result = modelRuntime.requireSession("local_greedy_frame").run(inputs)) {
                return new GreedyFrameResult(
                        extractBooleanScalar(result, "should_continue"),
                        flattenFrameTokenIds(result.get("frame_token_ids").orElseThrow().getValue())
                );
            }
        }
    }

    private GreedyFrameResult runLocalFixedSampledFrame(
            float[][] globalHidden,
            List<Set<Integer>> previousTokenSetsByChannel
    ) throws Exception {
        int nVq = modelRuntime.manifest().getAsJsonObject("tts_config").get("n_vq").getAsInt();
        int[][][] repetitionSeenMask = repetitionSeenMask(previousTokenSetsByChannel);
        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(modelRuntime.environment(), globalHidden);
             OnnxTensor repetitionSeenMaskTensor = OnnxTensor.createTensor(modelRuntime.environment(), repetitionSeenMask);
             OnnxTensor assistantRandomUTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(),
                     FloatBuffer.wrap(new float[]{boundedRandom()}),
                     new long[]{1}
             );
             OnnxTensor audioRandomUTensor = OnnxTensor.createTensor(modelRuntime.environment(), buildAudioRandomU(nVq))) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("repetition_seen_mask", repetitionSeenMaskTensor);
            inputs.put("assistant_random_u", assistantRandomUTensor);
            inputs.put("audio_random_u", audioRandomUTensor);
            try (OrtSession.Result result = modelRuntime.requireSession("local_fixed_sampled_frame").run(inputs)) {
                return new GreedyFrameResult(
                        extractBooleanScalar(result, "should_continue"),
                        flattenFrameTokenIds(result.get("frame_token_ids").orElseThrow().getValue())
                );
            }
        }
    }

    private int[][][] repetitionSeenMask(List<Set<Integer>> previousTokenSetsByChannel) {
        int nVq = modelRuntime.manifest().getAsJsonObject("tts_config").get("n_vq").getAsInt();
        int audioCodebookSize = modelRuntime.ttsMeta()
                .getAsJsonObject("model_config")
                .getAsJsonArray("audio_codebook_sizes")
                .get(0)
                .getAsInt();
        int[][][] mask = new int[1][nVq][audioCodebookSize];
        for (int channelIndex = 0; channelIndex < previousTokenSetsByChannel.size(); channelIndex++) {
            for (Integer tokenId : previousTokenSetsByChannel.get(channelIndex)) {
                if (tokenId != null && tokenId >= 0 && tokenId < audioCodebookSize) {
                    mask[0][channelIndex][tokenId] = 1;
                }
            }
        }
        return mask;
    }

    private LocalDecoderResult runLocalDecoder(
            float[][] globalHidden,
            int textTokenId,
            List<Integer> framePrefix
    ) throws Exception {
        JsonObject ttsConfig = modelRuntime.manifest().getAsJsonObject("tts_config");
        int nVq = ttsConfig.get("n_vq").getAsInt();
        int audioPad = ttsConfig.get("audio_pad_token_id").getAsInt();
        int[][] paddedPrefix = new int[1][nVq - 1];
        Arrays.fill(paddedPrefix[0], audioPad);
        for (int index = 0; index < Math.min(framePrefix.size(), nVq - 1); index++) {
            paddedPrefix[0][index] = framePrefix.get(index);
        }

        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(modelRuntime.environment(), globalHidden);
             OnnxTensor textTokenTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(), IntBuffer.wrap(new int[]{textTokenId}), new long[]{1}
             );
             OnnxTensor audioPrefixTensor = OnnxTensor.createTensor(modelRuntime.environment(), paddedPrefix)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("text_token_id", textTokenTensor);
            inputs.put("audio_prefix_token_ids", audioPrefixTensor);
            try (OrtSession.Result result = modelRuntime.requireSession("local_decoder").run(inputs)) {
                float[] textLogits = ((float[][]) result.get("text_logits").orElseThrow().getValue())[0];
                float[][] audioLogits = extractAudioLogits(
                        result.get("audio_logits").orElseThrow().getValue()
                );
                return new LocalDecoderResult(textLogits, audioLogits);
            }
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
        try (OnnxTensor globalHiddenTensor = OnnxTensor.createTensor(modelRuntime.environment(), globalHidden);
             OnnxTensor textTokenTensor = intScalar(textTokenId);
             OnnxTensor audioTokenTensor = intScalar(audioTokenId);
             OnnxTensor channelIndexTensor = intScalar(channelIndex);
             OnnxTensor stepTypeTensor = intScalar(stepType);
             OnnxTensor pastValidLengthsTensor = intScalar(pastValidLengths)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("global_hidden", globalHiddenTensor);
            inputs.put("text_token_id", textTokenTensor);
            inputs.put("audio_token_id", audioTokenTensor);
            inputs.put("channel_index", channelIndexTensor);
            inputs.put("step_type", stepTypeTensor);
            inputs.put("past_valid_lengths", pastValidLengthsTensor);
            inputs.putAll(localPastByName);

            try (OrtSession.Result result = modelRuntime.requireSession("local_cached_step").run(inputs)) {
                float[] textLogits = ((float[][]) result.get("text_logits").orElseThrow().getValue())[0];
                float[][] audioLogits = extractAudioLogits(
                        result.get("audio_logits").orElseThrow().getValue()
                );
                Map<String, OnnxTensor> nextLocalPast = new HashMap<>();
                JsonArray outputNames = modelRuntime.ttsMeta()
                        .getAsJsonObject("onnx")
                        .getAsJsonArray("local_cached_output_names");
                try {
                    for (int index = 2; index < outputNames.size(); index++) {
                        String outputName = outputNames.get(index).getAsString();
                        String nextName = outputName.replace("local_present_", "local_past_");
                        Object value = result.get(outputName).orElseThrow().getValue();
                        nextLocalPast.put(nextName, cloneTensor(value));
                    }
                } catch (Exception failure) {
                    closeTensors(nextLocalPast);
                    throw failure;
                }
                return new CachedStepResult(textLogits, audioLogits, nextLocalPast);
            }
        }
    }

    private Map<String, OnnxTensor> createEmptyLocalCachedPast() throws Exception {
        JsonObject modelConfig = modelRuntime.ttsMeta().getAsJsonObject("model_config");
        int localLayers = modelConfig.get("local_layers").getAsInt();
        int localHeads = modelConfig.get("local_heads").getAsInt();
        int localHeadDim = modelConfig.get("local_head_dim").getAsInt();
        if (localHeads <= 0 || localHeadDim <= 0) {
            throw new IllegalStateException(
                    "Invalid MOSS local cache shape: heads=" + localHeads + ", headDim=" + localHeadDim
            );
        }

        Map<String, OnnxTensor> result = new HashMap<>();
        long[] shape = new long[]{1, 0, localHeads, localHeadDim};
        try {
            for (int layerIndex = 0; layerIndex < localLayers; layerIndex++) {
                result.put(
                        "local_past_key_" + layerIndex,
                        OnnxTensor.createTensor(modelRuntime.environment(), FloatBuffer.wrap(new float[0]), shape)
                );
                result.put(
                        "local_past_value_" + layerIndex,
                        OnnxTensor.createTensor(modelRuntime.environment(), FloatBuffer.wrap(new float[0]), shape)
                );
            }
            return result;
        } catch (Exception failure) {
            closeTensors(result);
            throw failure;
        }
    }

    private JsonObject generationDefaults() {
        JsonObject generationDefaults = getOrCreateObject(modelRuntime.manifest(), "generation_defaults");
        return normalizeGenerationDefaults(generationDefaults);
    }

    static JsonObject normalizeGenerationDefaults(JsonObject generationDefaults) {
        ensureNumber(generationDefaults, "max_new_frames", 375);
        ensureBoolean(generationDefaults, "do_sample", true);
        ensureString(generationDefaults, "sample_mode", SAMPLE_MODE_FIXED);
        ensureNumber(generationDefaults, "text_temperature", 1.0f);
        ensureNumber(generationDefaults, "text_top_k", 50);
        ensureNumber(generationDefaults, "text_top_p", 1.0f);
        ensureNumber(generationDefaults, "audio_temperature", 0.8f);
        ensureNumber(generationDefaults, "audio_top_k", 25);
        ensureNumber(generationDefaults, "audio_top_p", 0.95f);
        ensureNumber(generationDefaults, "audio_repetition_penalty", 1.2f);
        String sampleMode = normalizeSampleMode(
                generationDefaults.get("sample_mode").getAsString(),
                generationDefaults.get("do_sample").getAsBoolean()
        );
        generationDefaults.addProperty("sample_mode", sampleMode);
        generationDefaults.addProperty("do_sample", !SAMPLE_MODE_GREEDY.equals(sampleMode));
        return generationDefaults;
    }

    static String normalizeSampleMode(String rawSampleMode, boolean doSample) {
        String normalized = rawSampleMode == null ? "" : rawSampleMode.trim().toLowerCase();
        if (SAMPLE_MODE_GREEDY.equals(normalized)) {
            return SAMPLE_MODE_GREEDY;
        }
        if (SAMPLE_MODE_FIXED.equals(normalized) || SAMPLE_MODE_FULL.equals(normalized)) {
            return doSample ? normalized : SAMPLE_MODE_GREEDY;
        }
        return doSample ? SAMPLE_MODE_FIXED : SAMPLE_MODE_GREEDY;
    }

    private int sampleAssistantTextToken(
            float[] textLogits,
            JsonObject generationDefaults,
            JsonObject ttsConfig
    ) {
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

    private int sampleAudioToken(
            float[] audioLogits,
            List<Integer> previousTokenIds,
            Set<Integer> previousTokenSet,
            JsonObject generationDefaults
    ) {
        float repetitionPenalty = generationDefaults.get("audio_repetition_penalty").getAsFloat();
        if (!generationDefaults.get("do_sample").getAsBoolean()) {
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

    private float[][] buildAudioRandomU(int nVq) {
        float[][] values = new float[1][nVq];
        for (int index = 0; index < nVq; index++) {
            values[0][index] = boundedRandom();
        }
        return values;
    }

    private float boundedRandom() {
        return Math.min(0.99999994f, Math.max(0.0f, random.nextFloat()));
    }

    private float[] sliceAudioChannelLogits(float[][] audioLogits, int channelIndex) {
        int nVq = modelRuntime.manifest().getAsJsonObject("tts_config").get("n_vq").getAsInt();
        int perChannel = audioLogits[0].length / nVq;
        int start = channelIndex * perChannel;
        return Arrays.copyOfRange(audioLogits[0], start, start + perChannel);
    }

    private static float[][] extractAudioLogits(Object value) {
        if (value instanceof float[][] tensor2d) {
            return tensor2d;
        }
        if (value instanceof float[][][] tensor3d && tensor3d.length > 0) {
            return tensor3d[0];
        }
        throw new IllegalArgumentException("Unsupported or empty audio_logits type: " + value.getClass());
    }

    private static float[][] extractLastHidden(float[][][] hiddenStates) {
        if (hiddenStates.length != 1) {
            throw new IllegalArgumentException("Unexpected global_hidden batch size: " + hiddenStates.length);
        }
        return new float[][]{hiddenStates[0][hiddenStates[0].length - 1]};
    }

    private OnnxTensor createInt32Tensor3D(List<int[]> inputIds) throws OrtException {
        int[][][] array = new int[1][inputIds.size()][inputIds.get(0).length];
        for (int index = 0; index < inputIds.size(); index++) {
            array[0][index] = inputIds.get(index);
        }
        return OnnxTensor.createTensor(modelRuntime.environment(), array);
    }

    private OnnxTensor createInt32Tensor2D(int[][] values) throws OrtException {
        return OnnxTensor.createTensor(modelRuntime.environment(), values);
    }

    private OnnxTensor intScalar(int value) throws OrtException {
        return OnnxTensor.createTensor(
                modelRuntime.environment(), IntBuffer.wrap(new int[]{value}), new long[]{1}
        );
    }

    private OnnxTensor cloneTensor(Object value) throws OrtException {
        if (value instanceof float[][][][] tensor4d) {
            return OnnxTensor.createTensor(modelRuntime.environment(), tensor4d);
        }
        if (value instanceof float[][][] tensor3d) {
            return OnnxTensor.createTensor(modelRuntime.environment(), tensor3d);
        }
        if (value instanceof float[][] tensor2d) {
            return OnnxTensor.createTensor(modelRuntime.environment(), tensor2d);
        }
        if (value instanceof int[] tensor1d) {
            return OnnxTensor.createTensor(
                    modelRuntime.environment(), IntBuffer.wrap(tensor1d), new long[]{tensor1d.length}
            );
        }
        if (value instanceof int[][] tensor2d) {
            return OnnxTensor.createTensor(modelRuntime.environment(), tensor2d);
        }
        if (value instanceof int[][][] tensor3d) {
            return OnnxTensor.createTensor(modelRuntime.environment(), tensor3d);
        }
        throw new IllegalArgumentException("Unsupported tensor clone type: " + value.getClass());
    }

    private static int sumAttentionMask(int[] attentionRow) {
        int total = 0;
        for (int value : attentionRow) {
            total += value;
        }
        return total;
    }

    private static boolean extractBooleanScalar(OrtSession.Result result, String outputName) throws Exception {
        Object value = result.get(outputName).orElseThrow().getValue();
        if (value instanceof boolean[] array && array.length > 0) return array[0];
        if (value instanceof long[] array && array.length > 0) return array[0] != 0L;
        if (value instanceof int[] array && array.length > 0) return array[0] != 0;
        if (value instanceof byte[] array && array.length > 0) return array[0] != 0;
        if (value instanceof int[][] array && array.length > 0 && array[0].length > 0) return array[0][0] != 0;
        if (value instanceof long[][] array && array.length > 0 && array[0].length > 0) return array[0][0] != 0L;
        if (value instanceof boolean[][] array && array.length > 0 && array[0].length > 0) return array[0][0];
        throw new IllegalArgumentException("Unsupported boolean scalar type: " + value.getClass());
    }

    private static List<Integer> flattenFrameTokenIds(Object value) {
        if (value instanceof int[] tensor1d) {
            List<Integer> frame = new ArrayList<>(tensor1d.length);
            for (int tokenId : tensor1d) frame.add(tokenId);
            return frame;
        }
        if (value instanceof long[] tensor1d) {
            List<Integer> frame = new ArrayList<>(tensor1d.length);
            for (long tokenId : tensor1d) frame.add((int) tokenId);
            return frame;
        }
        if (value instanceof int[][] tensor2d) {
            return flattenFrameTokenIds(tensor2d.length == 0 ? new int[0] : tensor2d[0]);
        }
        if (value instanceof long[][] tensor2d) {
            return flattenFrameTokenIds(tensor2d.length == 0 ? new long[0] : tensor2d[0]);
        }
        throw new IllegalArgumentException("Unsupported frame_token_ids type: " + value.getClass());
    }

    private static JsonObject getOrCreateObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        JsonObject child = new JsonObject();
        object.add(name, child);
        return child;
    }

    private static void ensureBoolean(JsonObject object, String name, boolean value) {
        if (!object.has(name) || object.get(name).isJsonNull()) object.addProperty(name, value);
    }

    private static void ensureString(JsonObject object, String name, String value) {
        if (!object.has(name) || object.get(name).isJsonNull()) object.addProperty(name, value);
    }

    private static void ensureNumber(JsonObject object, String name, Number value) {
        if (!object.has(name) || object.get(name).isJsonNull()) object.addProperty(name, value);
    }

    private static void closeValue(OnnxValue value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeTensors(Map<String, OnnxTensor> tensors) {
        if (tensors == null) return;
        for (OnnxTensor tensor : tensors.values()) {
            try {
                tensor.close();
            } catch (Exception ignored) {
            }
        }
    }

    @FunctionalInterface
    interface FrameCallback {
        void onFrame(List<List<Integer>> generatedFrames, int stepIndex, List<Integer> frame) throws Exception;
    }

    private record LocalDecoderResult(float[] textLogits, float[][] audioLogits) {
    }

    private record CachedStepResult(
            float[] textLogits,
            float[][] audioLogits,
            Map<String, OnnxTensor> nextLocalPast
    ) {
    }

    private record DecodeStepResult(float[][] globalHidden, Map<String, OnnxTensor> nextPastByName) {
    }

    private record GreedyFrameResult(boolean shouldContinue, List<Integer> frame) {
    }
}
