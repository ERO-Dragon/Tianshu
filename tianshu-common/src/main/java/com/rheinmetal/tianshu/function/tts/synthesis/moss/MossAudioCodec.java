package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns MOSS prompt encoding and full/streaming audio codec execution. */
final class MossAudioCodec {
    private static final int STREAMING_DECODE_CHUNK_FRAMES = 4;
    private static final int FALLBACK_DECODE_CHUNK_FRAMES = 8;

    private final IGameEnvironment env;
    private final MossModelRuntime modelRuntime;

    MossAudioCodec(IGameEnvironment env, MossModelRuntime modelRuntime) {
        this.env = env;
        this.modelRuntime = modelRuntime;
    }

    static int streamingDecodeChunkFrames() {
        return STREAMING_DECODE_CHUNK_FRAMES;
    }

    int channels() {
        return Math.max(
                1,
                modelRuntime.codecMeta().getAsJsonObject("codec_config").get("channels").getAsInt()
        );
    }

    List<List<Integer>> encodePromptAudioCodes(Path wavPath) throws Exception {
        if (wavPath == null || !Files.exists(wavPath)) {
            throw new IOException("MOSS reference audio file does not exist: " + wavPath);
        }

        int targetSampleRate = modelRuntime.sampleRate();
        float[][] pcmChannels = readWavAsFloatChannels(wavPath);
        float[][] promptChannels = prepareCodecChannels(pcmChannels, channels());
        promptChannels = resampleIfNeeded(
                promptChannels,
                (int) readWavSampleRate(wavPath),
                targetSampleRate
        );

        float[][][] waveform = new float[][][]{promptChannels};
        int waveformLength = promptChannels.length == 0 ? 0 : promptChannels[0].length;
        try (OnnxTensor waveformTensor = OnnxTensor.createTensor(modelRuntime.environment(), waveform);
             OnnxTensor lengthsTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(),
                     IntBuffer.wrap(new int[]{waveformLength}),
                     new long[]{1}
             )) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("waveform", waveformTensor);
            inputs.put("input_lengths", lengthsTensor);
            try (OrtSession.Result result = modelRuntime.requireSession("codec_encode").run(inputs)) {
                int[][][] audioCodes = (int[][][]) result.get("audio_codes").orElseThrow().getValue();
                int codeLength = ((int[]) result.get("audio_code_lengths").orElseThrow().getValue())[0];
                int numQuantizers = modelRuntime.codecMeta()
                        .getAsJsonObject("codec_config")
                        .get("num_quantizers")
                        .getAsInt();

                List<List<Integer>> promptAudioCodes = new ArrayList<>(codeLength);
                for (int frameIndex = 0; frameIndex < codeLength; frameIndex++) {
                    List<Integer> frame = new ArrayList<>(numQuantizers);
                    for (int quantizer = 0; quantizer < numQuantizers; quantizer++) {
                        frame.add(audioCodes[0][frameIndex][quantizer]);
                    }
                    promptAudioCodes.add(frame);
                }
                return promptAudioCodes;
            }
        }
    }

    MossTtsService.DecodeResult decodeFullAudio(List<List<Integer>> generatedFrames) throws Exception {
        if (generatedFrames.isEmpty()) {
            return emptyResult();
        }
        int[][][] audioCodes = toAudioCodes(generatedFrames);
        try (OnnxTensor audioCodesTensor = OnnxTensor.createTensor(modelRuntime.environment(), audioCodes);
             OnnxTensor lengthsTensor = OnnxTensor.createTensor(
                     modelRuntime.environment(),
                     IntBuffer.wrap(new int[]{generatedFrames.size()}),
                     new long[]{1}
             )) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("audio_codes", audioCodesTensor);
            inputs.put("audio_code_lengths", lengthsTensor);
            try (OrtSession.Result result = modelRuntime.requireSession("codec_decode").run(inputs)) {
                float[][][] audio = (float[][][]) result.get("audio").orElseThrow().getValue();
                int audioLength = ((int[]) result.get("audio_lengths").orElseThrow().getValue())[0];
                return new MossTtsService.DecodeResult(
                        sliceChannelMajorAudio(audio, 0, audioLength),
                        audioLength
                );
            }
        }
    }

    MossTtsService.DecodeResult decodeFullAudioSafe(List<List<Integer>> generatedFrames) throws Exception {
        if (generatedFrames.isEmpty()) {
            return emptyResult();
        }
        try {
            return decodeFullAudio(generatedFrames);
        } catch (Exception failure) {
            env.warn("MOSS full codec decode failed; using streaming fallback: " + failure.getMessage());
            return decodeStreamingAudio(generatedFrames);
        }
    }

    StreamingDecoder openStreamingDecoder() throws OrtException {
        return new StreamingDecoder();
    }

    private MossTtsService.DecodeResult decodeStreamingAudio(List<List<Integer>> generatedFrames) throws Exception {
        requireStreamingDecode();
        Map<String, OnnxTensor> stateFeeds = createStreamingDecodeState();
        Map<String, String> outputToInputName = buildStreamingOutputToInputMapping();
        List<float[][]> audioChunks = new ArrayList<>();
        try {
            for (int startIndex = 0; startIndex < generatedFrames.size(); startIndex += FALLBACK_DECODE_CHUNK_FRAMES) {
                int endIndex = Math.min(
                        startIndex + FALLBACK_DECODE_CHUNK_FRAMES,
                        generatedFrames.size()
                );
                List<List<Integer>> frameChunk = generatedFrames.subList(startIndex, endIndex);
                try (OnnxTensor audioCodesTensor = OnnxTensor.createTensor(
                             modelRuntime.environment(), toAudioCodes(frameChunk)
                     );
                     OnnxTensor audioLengthsTensor = OnnxTensor.createTensor(
                             modelRuntime.environment(),
                             IntBuffer.wrap(new int[]{frameChunk.size()}),
                             new long[]{1}
                     )) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("audio_codes", audioCodesTensor);
                    inputs.put("audio_code_lengths", audioLengthsTensor);
                    inputs.putAll(stateFeeds);
                    try (OrtSession.Result result = modelRuntime.requireSession("codec_decode_step").run(inputs)) {
                        float[][][] audio = (float[][][]) result.get("audio").orElseThrow().getValue();
                        int audioLength = ((int[]) result.get("audio_lengths").orElseThrow().getValue())[0];
                        if (audioLength > 0) {
                            audioChunks.add(sliceChannelMajorAudio(audio, 0, audioLength));
                        }
                        replaceStreamingState(result, stateFeeds, outputToInputName);
                    }
                }
            }
        } finally {
            closeTensors(stateFeeds);
        }
        float[][] channels = mergeChannelChunks(audioChunks);
        return new MossTtsService.DecodeResult(
                channels,
                channels.length == 0 ? 0 : channels[0].length
        );
    }

    final class StreamingDecoder implements AutoCloseable {
        private final Map<String, OnnxTensor> stateFeeds;
        private final Map<String, String> outputToInputName;
        private final List<List<Integer>> pendingFrames = new ArrayList<>();
        private boolean closed;

        private StreamingDecoder() throws OrtException {
            requireStreamingDecode();
            this.stateFeeds = createStreamingDecodeState();
            this.outputToInputName = buildStreamingOutputToInputMapping();
        }

        MossTtsService.DecodeResult acceptFrame(List<Integer> frame) throws Exception {
            if (frame != null && !frame.isEmpty()) {
                pendingFrames.add(List.copyOf(frame));
            }
            if (pendingFrames.size() < STREAMING_DECODE_CHUNK_FRAMES) {
                return emptyResult();
            }
            return decodePending(STREAMING_DECODE_CHUNK_FRAMES);
        }

        MossTtsService.DecodeResult flush() throws Exception {
            if (pendingFrames.isEmpty()) {
                return emptyResult();
            }
            return decodePending(pendingFrames.size());
        }

        private MossTtsService.DecodeResult decodePending(int frameCount) throws Exception {
            List<List<Integer>> frameChunk = new ArrayList<>(pendingFrames.subList(0, frameCount));
            pendingFrames.subList(0, frameCount).clear();
            try (OnnxTensor audioCodesTensor = OnnxTensor.createTensor(
                         modelRuntime.environment(), toAudioCodes(frameChunk)
                 );
                 OnnxTensor audioLengthsTensor = OnnxTensor.createTensor(
                         modelRuntime.environment(),
                         IntBuffer.wrap(new int[]{frameCount}),
                         new long[]{1}
                 )) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("audio_codes", audioCodesTensor);
                inputs.put("audio_code_lengths", audioLengthsTensor);
                inputs.putAll(stateFeeds);
                try (OrtSession.Result result = modelRuntime.requireSession("codec_decode_step").run(inputs)) {
                    float[][][] audio = (float[][][]) result.get("audio").orElseThrow().getValue();
                    int audioLength = ((int[]) result.get("audio_lengths").orElseThrow().getValue())[0];
                    MossTtsService.DecodeResult decoded = new MossTtsService.DecodeResult(
                            sliceChannelMajorAudio(audio, 0, audioLength),
                            audioLength
                    );
                    replaceStreamingState(result, stateFeeds, outputToInputName);
                    return decoded;
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            pendingFrames.clear();
            closeTensors(stateFeeds);
        }
    }

    private void requireStreamingDecode() {
        if (!modelRuntime.hasSession("codec_decode_step")) {
            throw new IllegalStateException("MOSS codec_decode_step session is unavailable");
        }
        if (!modelRuntime.codecMeta().has("streaming_decode")) {
            throw new IllegalStateException("MOSS codec metadata has no streaming_decode section");
        }
    }

    private void replaceStreamingState(
            OrtSession.Result result,
            Map<String, OnnxTensor> stateFeeds,
            Map<String, String> outputToInputName
    ) throws Exception {
        JsonArray outputNames = modelRuntime.codecMeta()
                .getAsJsonObject("onnx")
                .getAsJsonArray("decode_step_output_names");
        for (int index = 2; index < outputNames.size(); index++) {
            String outputName = outputNames.get(index).getAsString();
            Object value = result.get(outputName).orElseThrow().getValue();
            String inputName = outputToInputName.getOrDefault(outputName, outputName);
            OnnxTensor replacement = cloneTensor(value);
            closeValue(stateFeeds.put(inputName, replacement));
        }
    }

    private Map<String, OnnxTensor> createStreamingDecodeState() throws OrtException {
        JsonObject streamingDecode = modelRuntime.codecMeta().getAsJsonObject("streaming_decode");
        Map<String, OnnxTensor> state = new HashMap<>();
        try {
            JsonArray transformerOffsets = streamingDecode.getAsJsonArray("transformer_offsets");
            for (JsonObject spec : objects(transformerOffsets)) {
                long[] shape = jsonArrayToLongArray(spec.getAsJsonArray("shape"));
                IntBuffer zeros = IntBuffer.wrap(new int[Math.toIntExact(product(shape))]);
                state.put(
                        spec.get("input_name").getAsString(),
                        OnnxTensor.createTensor(modelRuntime.environment(), zeros, shape)
                );
            }

            JsonArray attentionCaches = streamingDecode.getAsJsonArray("attention_caches");
            for (JsonObject spec : objects(attentionCaches)) {
                long[] offsetShape = jsonArrayToLongArray(spec.getAsJsonArray("offset_shape"));
                state.put(
                        spec.get("offset_input_name").getAsString(),
                        OnnxTensor.createTensor(
                                modelRuntime.environment(),
                                IntBuffer.wrap(new int[Math.toIntExact(product(offsetShape))]),
                                offsetShape
                        )
                );

                long[] cacheShape = jsonArrayToLongArray(spec.getAsJsonArray("cache_shape"));
                state.put(
                        spec.get("cached_keys_input_name").getAsString(),
                        OnnxTensor.createTensor(
                                modelRuntime.environment(),
                                FloatBuffer.wrap(new float[Math.toIntExact(product(cacheShape))]),
                                cacheShape
                        )
                );
                state.put(
                        spec.get("cached_values_input_name").getAsString(),
                        OnnxTensor.createTensor(
                                modelRuntime.environment(),
                                FloatBuffer.wrap(new float[Math.toIntExact(product(cacheShape))]),
                                cacheShape
                        )
                );

                long[] positionsShape = jsonArrayToLongArray(spec.getAsJsonArray("positions_shape"));
                int[] positions = new int[Math.toIntExact(product(positionsShape))];
                Arrays.fill(positions, -1);
                state.put(
                        spec.get("cached_positions_input_name").getAsString(),
                        OnnxTensor.createTensor(
                                modelRuntime.environment(),
                                IntBuffer.wrap(positions),
                                positionsShape
                        )
                );
            }
            return state;
        } catch (OrtException | RuntimeException failure) {
            closeTensors(state);
            throw failure;
        }
    }

    private Map<String, String> buildStreamingOutputToInputMapping() {
        JsonObject streamingDecode = modelRuntime.codecMeta().getAsJsonObject("streaming_decode");
        Map<String, String> mapping = new HashMap<>();
        for (JsonObject spec : objects(streamingDecode.getAsJsonArray("transformer_offsets"))) {
            mapping.put(spec.get("output_name").getAsString(), spec.get("input_name").getAsString());
        }
        for (JsonObject spec : objects(streamingDecode.getAsJsonArray("attention_caches"))) {
            mapping.put(spec.get("offset_output_name").getAsString(), spec.get("offset_input_name").getAsString());
            mapping.put(
                    spec.get("cached_keys_output_name").getAsString(),
                    spec.get("cached_keys_input_name").getAsString()
            );
            mapping.put(
                    spec.get("cached_values_output_name").getAsString(),
                    spec.get("cached_values_input_name").getAsString()
            );
            mapping.put(
                    spec.get("cached_positions_output_name").getAsString(),
                    spec.get("cached_positions_input_name").getAsString()
            );
        }
        return mapping;
    }

    static float[][] mergeChannelChunks(List<float[][]> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return new float[0][];
        }
        int channels = chunks.get(0).length;
        int totalSamples = 0;
        for (float[][] chunk : chunks) {
            if (chunk.length != channels) {
                throw new IllegalArgumentException("MOSS codec chunk channel count changed during decode");
            }
            totalSamples += chunk.length == 0 ? 0 : chunk[0].length;
        }

        float[][] merged = new float[channels][totalSamples];
        int offset = 0;
        for (float[][] chunk : chunks) {
            int chunkSamples = chunk.length == 0 ? 0 : chunk[0].length;
            for (int channel = 0; channel < channels; channel++) {
                if (chunk[channel].length != chunkSamples) {
                    throw new IllegalArgumentException("MOSS codec chunk channels have different lengths");
                }
                System.arraycopy(chunk[channel], 0, merged[channel], offset, chunkSamples);
            }
            offset += chunkSamples;
        }
        return merged;
    }

    private static List<JsonObject> objects(JsonArray array) {
        List<JsonObject> result = new ArrayList<>(array.size());
        array.forEach(element -> result.add(element.getAsJsonObject()));
        return result;
    }

    private static int[][][] toAudioCodes(List<List<Integer>> frames) {
        int codeWidth = frames.get(0).size();
        int[][][] audioCodes = new int[1][frames.size()][codeWidth];
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            List<Integer> frame = frames.get(frameIndex);
            if (frame.size() != codeWidth) {
                throw new IllegalArgumentException("MOSS audio-code frame width changed during decode");
            }
            for (int codeIndex = 0; codeIndex < codeWidth; codeIndex++) {
                audioCodes[0][frameIndex][codeIndex] = frame.get(codeIndex);
            }
        }
        return audioCodes;
    }

    private static MossTtsService.DecodeResult emptyResult() {
        return new MossTtsService.DecodeResult(new float[0][], 0);
    }

    private static float[][] sliceChannelMajorAudio(float[][][] audio, int startSample, int endSample) {
        int channels = audio[0].length;
        float[][] result = new float[channels][];
        for (int channelIndex = 0; channelIndex < channels; channelIndex++) {
            result[channelIndex] = Arrays.copyOfRange(audio[0][channelIndex], startSample, endSample);
        }
        return result;
    }

    private float[][] readWavAsFloatChannels(Path wavPath) throws Exception {
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    Math.max(1, sourceFormat.getChannels()),
                    Math.max(1, sourceFormat.getChannels()) * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, sourceStream)) {
                byte[] bytes = readAllBytes(pcmStream);
                int channels = pcmFormat.getChannels();
                int frameSize = pcmFormat.getFrameSize();
                int totalSamples = frameSize <= 0 ? 0 : bytes.length / frameSize;
                float[][] channelData = new float[channels][totalSamples];
                for (int sampleIndex = 0; sampleIndex < totalSamples; sampleIndex++) {
                    for (int channel = 0; channel < channels; channel++) {
                        int offset = sampleIndex * frameSize + channel * 2;
                        short sample = (short) ((bytes[offset + 1] << 8) | (bytes[offset] & 0xFF));
                        channelData[channel][sampleIndex] = Math.max(
                                -1.0f,
                                Math.min(1.0f, sample / 32768.0f)
                        );
                    }
                }
                return channelData;
            }
        }
    }

    private static byte[] readAllBytes(AudioInputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static float readWavSampleRate(Path wavPath) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(wavPath.toFile())) {
            return stream.getFormat().getSampleRate();
        }
    }

    private static float[][] prepareCodecChannels(float[][] source, int targetChannels) {
        int channels = Math.max(1, targetChannels);
        int samples = source != null && source.length > 0 ? source[0].length : 0;
        float[][] result = new float[channels][samples];
        if (source == null || source.length == 0) {
            return result;
        }
        for (int channel = 0; channel < channels; channel++) {
            float[] sourceChannel = source[Math.min(channel, source.length - 1)];
            System.arraycopy(sourceChannel, 0, result[channel], 0, Math.min(samples, sourceChannel.length));
        }
        return result;
    }

    private static float[][] resampleIfNeeded(float[][] channels, int sourceRate, int targetRate) {
        if (sourceRate == targetRate) {
            return channels;
        }
        double ratio = (double) targetRate / sourceRate;
        int sourceLength = channels.length == 0 ? 0 : channels[0].length;
        int newLength = (int) (sourceLength * ratio);
        float[][] result = new float[channels.length][newLength];
        for (int channel = 0; channel < channels.length; channel++) {
            float[] source = channels[channel];
            for (int index = 0; index < newLength; index++) {
                double sourcePosition = index / ratio;
                int sourceIndex = (int) sourcePosition;
                if (sourceIndex + 1 < source.length) {
                    float fraction = (float) (sourcePosition - sourceIndex);
                    result[channel][index] = source[sourceIndex] * (1 - fraction)
                            + source[sourceIndex + 1] * fraction;
                } else if (sourceIndex < source.length) {
                    result[channel][index] = source[sourceIndex];
                }
            }
        }
        return result;
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

    private static long[] jsonArrayToLongArray(JsonArray array) {
        long[] result = new long[array.size()];
        for (int index = 0; index < array.size(); index++) {
            result[index] = array.get(index).getAsLong();
        }
        return result;
    }

    private static long product(long[] values) {
        long product = 1;
        for (long value : values) {
            product = Math.multiplyExact(product, value);
        }
        return product;
    }

    private static void closeValue(OnnxValue value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeTensors(Map<String, OnnxTensor> tensors) {
        if (tensors == null) {
            return;
        }
        for (OnnxTensor tensor : tensors.values()) {
            closeValue(tensor);
        }
        tensors.clear();
    }
}
