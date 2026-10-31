package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.sentencepiece.SentencePieceProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ONNX Runtime assembly for the Pocket-TTS-ZH-4L delivery.
 *
 * <p>The four FlowLM/Mimi graphs and the conditioner are owned by one instance; the glue tensors
 * (speaker projection, latent statistics and voice BOS) come from {@code ort_glue.npz}. Prompt
 * post-processing (WAV decode, resample, silence trimming) stays in {@link PocketTtsService}.</p>
 */
final class PocketModelRuntime implements AutoCloseable {
    static final int SAMPLE_RATE = 24_000;
    static final int DIM = 1024;
    static final int LATENT_DIM = 32;
    static final int LAYERS = 4;
    static final int HEADS = 16;
    static final int HEAD_DIM = 64;
    private static final int MIMI_FRAME_SIZE = 1920;

    private final IGameEnvironment env;
    private final Path modelDir;
    private final InferenceResourcePolicy resourcePolicy;
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private OrtEnvironment environment;
    private SentencePieceProcessor tokenizer;
    private Glue glue;

    PocketModelRuntime(IGameEnvironment env, Path modelDir, InferenceResourcePolicy resourcePolicy) {
        this.env = env;
        this.modelDir = modelDir;
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
    }

    synchronized void initialize() throws Exception {
        if (!sessions.isEmpty()) {
            return;
        }
        NativeLibraryLoader.ensureLoaded();
        environment = OrtEnvironment.getEnvironment();
        int threads = resourcePolicy.pocketTtsThreads();
        for (String name : List.of("conditioner", "flowlm_prefill", "flowlm_step", "mimi_encode", "mimi_decode_int8")) {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(threads);
            sessions.put(name, environment.createSession(
                    modelDir.resolve(fileName(name)).toString(), options));
        }
        tokenizer = new SentencePieceProcessor(modelDir.resolve("tokenizer.model"));
        glue = Glue.load(modelDir.resolve("ort_glue.npz"));
        env.info("Pocket-TTS runtime initialized: threads=" + threads);
    }

    private static String fileName(String session) {
        return switch (session) {
            case "conditioner" -> "conditioner.onnx";
            case "flowlm_prefill" -> "flowlm_prefill.onnx";
            case "flowlm_step" -> "flowlm_step.onnx";
            case "mimi_encode" -> "mimi_encode.onnx";
            case "mimi_decode_int8" -> "mimi_decode_int8.onnx";
            default -> throw new IllegalArgumentException("Unknown Pocket session: " + session);
        };
    }

    int sampleRate() {
        return SAMPLE_RATE;
    }

    SentencePieceProcessor tokenizer() {
        return tokenizer;
    }

    Glue glue() {
        return glue;
    }

    float[][] conditioner(int[] tokens) throws Exception {
        long[] ids = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            ids[i] = tokens[i];
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), new long[]{1, tokens.length});
             OrtSession.Result result = require("conditioner").run(Map.of("tokens", tensor))) {
            return ((float[][][]) result.get("embeddings").orElseThrow().getValue())[0];
        }
    }

    float[][] encodeVoice(float[] pcm24k) throws Exception {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pcm24k), new long[]{1, 1, pcm24k.length});
             OrtSession.Result result = require("mimi_encode").run(Map.of("wav", tensor))) {
            return ((float[][][]) result.get("latents").orElseThrow().getValue())[0];
        }
    }

    float[][] projectVoice(float[][] latents) {
        float[] weight = glue.speakerProjection;
        float[][] out = new float[latents.length][DIM];
        for (int t = 0; t < latents.length; t++) {
            for (int o = 0; o < DIM; o++) {
                float sum = 0f;
                int base = o * LATENT_DIM;
                for (int c = 0; c < LATENT_DIM; c++) {
                    sum += weight[base + c] * latents[t][c];
                }
                out[t][o] = sum;
            }
        }
        return out;
    }

    float[][] prependBos(float[][] conditioning) {
        float[][] out = new float[conditioning.length + 1][DIM];
        System.arraycopy(glue.bosBeforeVoice, 0, out[0], 0, DIM);
        System.arraycopy(conditioning, 0, out, 1, conditioning.length);
        return out;
    }

    State newState(int capacity) {
        return new State(capacity);
    }

    void prefill(float[][] conditioning, State state) throws Exception {
        int t = conditioning.length;
        int off = state.offset;
        int tk = off + t;
        if (tk > state.capacity) {
            throw new IllegalStateException("Pocket FlowLM state capacity exceeded");
        }
        float[] condFlat = new float[t * DIM];
        for (int i = 0; i < t; i++) {
            System.arraycopy(conditioning[i], 0, condFlat, i * DIM, DIM);
        }
        float[] mask = new float[t * tk];
        for (int q = 0; q < t; q++) {
            for (int k = 0; k < tk; k++) {
                mask[q * tk + k] = k <= off + q ? 0f : -1e9f;
            }
        }

        Map<String, OnnxTensor> feeds = new HashMap<>();
        List<OnnxTensor> owned = new ArrayList<>();
        feeds.put("cond", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(condFlat), new long[]{1, t, DIM})));
        feeds.put("mask", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(mask), new long[]{1, 1, t, tk})));
        feeds.put("offset", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[]{off}), new long[]{1})));
        for (int layer = 0; layer < LAYERS; layer++) {
            feeds.put("k_past_" + layer, own(owned, stateTensor(state.keys[layer], off)));
            feeds.put("v_past_" + layer, own(owned, stateTensor(state.values[layer], off)));
        }
        try (OrtSession.Result result = require("flowlm_prefill").run(feeds)) {
            for (int layer = 0; layer < LAYERS; layer++) {
                state.writeKeys(layer, off, (float[][][][]) result.get("k_new_" + layer).orElseThrow().getValue());
                state.writeValues(layer, off, (float[][][][]) result.get("v_new_" + layer).orElseThrow().getValue());
            }
        } finally {
            closeAll(owned);
        }
        state.offset = tk;
    }

    Step step(float[] latent, float[] noise, State state) throws Exception {
        int off = state.offset;
        Map<String, OnnxTensor> feeds = new HashMap<>();
        List<OnnxTensor> owned = new ArrayList<>();
        feeds.put("latent", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(latent), new long[]{1, 1, LATENT_DIM})));
        feeds.put("noise", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(noise), new long[]{1, LATENT_DIM})));
        feeds.put("offset", own(owned, OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[]{off}), new long[]{1})));
        for (int layer = 0; layer < LAYERS; layer++) {
            feeds.put("k_past_" + layer, own(owned, stateTensor(state.keys[layer], off)));
            feeds.put("v_past_" + layer, own(owned, stateTensor(state.values[layer], off)));
        }
        try (OrtSession.Result result = require("flowlm_step").run(feeds)) {
            for (int layer = 0; layer < LAYERS; layer++) {
                state.writeKeys(layer, off, (float[][][][]) result.get("k_new_" + layer).orElseThrow().getValue());
                state.writeValues(layer, off, (float[][][][]) result.get("v_new_" + layer).orElseThrow().getValue());
            }
            state.offset++;
            float[] next = ((float[][]) result.get("latent_out").orElseThrow().getValue())[0];
            float eos = ((float[][]) result.get("eos_logit").orElseThrow().getValue())[0][0];
            return new Step(next, eos > 0.0f);
        } finally {
            closeAll(owned);
        }
    }

    float[] decode(List<float[]> latents, int batchSize) throws Exception {
        MimiState mimi = new MimiState();
        List<Float> samples = new ArrayList<>();
        boolean first = true;
        for (int start = 0; start < latents.size(); start += batchSize) {
            int end = Math.min(latents.size(), start + batchSize);
            int t = end - start;
            float[] latent = new float[t * LATENT_DIM];
            for (int i = 0; i < t; i++) {
                for (int c = 0; c < LATENT_DIM; c++) {
                    latent[i * LATENT_DIM + c] = latents.get(start + i)[c] * glue.latentStd[c] + glue.latentMean[c];
                }
            }
            float[] chunk = mimi.decode(this, latent, t);
            if (first) {
                int fade = Math.min(120, chunk.length);
                for (int i = 0; i < fade; i++) {
                    chunk[i] *= i / (float) fade;
                }
                first = false;
            }
            for (float sample : chunk) {
                samples.add(sample);
            }
        }
        float[] out = new float[samples.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = samples.get(i);
        }
        return out;
    }

    private OnnxTensor stateTensor(float[] state, int length) throws OrtException {
        return OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(Arrays.copyOf(state, length * HEADS * HEAD_DIM)),
                new long[]{1, length, HEADS, HEAD_DIM});
    }

    private static OnnxTensor own(List<OnnxTensor> owned, OnnxTensor tensor) {
        owned.add(tensor);
        return tensor;
    }

    private static void closeAll(List<OnnxTensor> owned) {
        for (OnnxTensor tensor : owned) {
            try {
                tensor.close();
            } catch (Exception ignored) {
                // A failed close must not hide the inference failure that brought us here.
            }
        }
    }

    private OrtSession require(String name) {
        OrtSession session = sessions.get(name);
        if (session == null) {
            throw new IllegalStateException("Pocket session is unavailable: " + name);
        }
        return session;
    }

    @Override
    public synchronized void close() {
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception e) {
                env.warn("Pocket-TTS session close failed: " + e.getMessage());
            }
        }
        sessions.clear();
        glue = null;
        tokenizer = null;
    }

    record Step(float[] latent, boolean eos) {
    }

    static final class State {
        final float[][] keys = new float[LAYERS][];
        final float[][] values = new float[LAYERS][];
        final int capacity;
        int offset;

        State(int capacity) {
            this.capacity = capacity;
            for (int layer = 0; layer < LAYERS; layer++) {
                keys[layer] = new float[capacity * HEADS * HEAD_DIM];
                values[layer] = new float[capacity * HEADS * HEAD_DIM];
            }
        }

        void writeKeys(int layer, int off, float[][][][] out) {
            write(keys[layer], off, out);
        }

        void writeValues(int layer, int off, float[][][][] out) {
            write(values[layer], off, out);
        }

        private static void write(float[] target, int off, float[][][][] out) {
            int t = out[0].length;
            for (int i = 0; i < t; i++) {
                for (int head = 0; head < HEADS; head++) {
                    for (int dim = 0; dim < HEAD_DIM; dim++) {
                        target[(off + i) * HEADS * HEAD_DIM + head * HEAD_DIM + dim] = out[0][i][head][dim];
                    }
                }
            }
        }
    }

    private static final class MimiState {
        float[] up = new float[512 * 16];
        float[] d0 = new float[512 * 6];
        float[] d2 = new float[256 * 6];
        float[] d3 = new float[256 * 2];
        float[] d5 = new float[128 * 5];
        float[] d6 = new float[128 * 2];
        float[] d8 = new float[64 * 4];
        float[] d9 = new float[64 * 2];
        float[] d11 = new float[64 * 2];
        long a0Offset;
        long a1Offset;
        float[] a0k = new float[0];
        float[] a0v = new float[0];
        float[] a1k = new float[0];
        float[] a1v = new float[0];

        float[] decode(PocketModelRuntime runtime, float[] latent, int t) throws Exception {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            List<OnnxTensor> owned = new ArrayList<>();
            feeds.put("latent", own(owned, OnnxTensor.createTensor(runtime.environment,
                    FloatBuffer.wrap(latent), new long[]{t, LATENT_DIM})));
            feeds.put("up_partial", own(owned, tensor(runtime, up, new long[]{512, 16})));
            feeds.put("d0_prev", own(owned, tensor(runtime, d0, new long[]{512, 6})));
            feeds.put("d2_partial", own(owned, tensor(runtime, d2, new long[]{256, 6})));
            feeds.put("d3a_prev", own(owned, tensor(runtime, d3, new long[]{256, 2})));
            feeds.put("d5_partial", own(owned, tensor(runtime, d5, new long[]{128, 5})));
            feeds.put("d6a_prev", own(owned, tensor(runtime, d6, new long[]{128, 2})));
            feeds.put("d8_partial", own(owned, tensor(runtime, d8, new long[]{64, 4})));
            feeds.put("d9a_prev", own(owned, tensor(runtime, d9, new long[]{64, 2})));
            feeds.put("d11_prev", own(owned, tensor(runtime, d11, new long[]{64, 2})));
            feeds.put("a0_offset", own(owned, OnnxTensor.createTensor(runtime.environment,
                    LongBuffer.wrap(new long[]{a0Offset}), new long[]{})));
            feeds.put("a0_k", own(owned, tensor(runtime, a0k, new long[]{a0Offset, 8, 64})));
            feeds.put("a0_v", own(owned, tensor(runtime, a0v, new long[]{a0Offset, 8, 64})));
            feeds.put("a1_offset", own(owned, OnnxTensor.createTensor(runtime.environment,
                    LongBuffer.wrap(new long[]{a1Offset}), new long[]{})));
            feeds.put("a1_k", own(owned, tensor(runtime, a1k, new long[]{a1Offset, 8, 64})));
            feeds.put("a1_v", own(owned, tensor(runtime, a1v, new long[]{a1Offset, 8, 64})));
            try (OrtSession.Result result = runtime.require("mimi_decode_int8").run(feeds)) {
                float[] audio = ((float[][][]) result.get("audio").orElseThrow().getValue())[0][0];
                up = flatten((float[][]) result.get("up_partial_out").orElseThrow().getValue());
                d0 = flatten((float[][]) result.get("d0_prev_out").orElseThrow().getValue());
                d2 = flatten((float[][]) result.get("d2_partial_out").orElseThrow().getValue());
                d3 = flatten((float[][]) result.get("d3a_prev_out").orElseThrow().getValue());
                d5 = flatten((float[][]) result.get("d5_partial_out").orElseThrow().getValue());
                d6 = flatten((float[][]) result.get("d6a_prev_out").orElseThrow().getValue());
                d8 = flatten((float[][]) result.get("d8_partial_out").orElseThrow().getValue());
                d9 = flatten((float[][]) result.get("d9a_prev_out").orElseThrow().getValue());
                d11 = flatten((float[][]) result.get("d11_prev_out").orElseThrow().getValue());
                a0Offset = ((long[]) result.get("a0_offset_out").orElseThrow().getValue())[0];
                a1Offset = ((long[]) result.get("a1_offset_out").orElseThrow().getValue())[0];
                a0k = flatten3((float[][][]) result.get("a0_k_out").orElseThrow().getValue());
                a0v = flatten3((float[][][]) result.get("a0_v_out").orElseThrow().getValue());
                a1k = flatten3((float[][][]) result.get("a1_k_out").orElseThrow().getValue());
                a1v = flatten3((float[][][]) result.get("a1_v_out").orElseThrow().getValue());
                return audio;
            } finally {
                closeAll(owned);
            }
        }

        private static OnnxTensor tensor(PocketModelRuntime runtime, float[] flat, long[] shape) throws OrtException {
            return OnnxTensor.createTensor(runtime.environment, FloatBuffer.wrap(flat), shape);
        }

        private static float[] flatten(float[][] value) {
            int width = value[0].length;
            float[] out = new float[value.length * width];
            for (int i = 0; i < value.length; i++) {
                System.arraycopy(value[i], 0, out, i * width, width);
            }
            return out;
        }

        private static float[] flatten3(float[][][] value) {
            int size = value.length * value[0].length * value[0][0].length;
            float[] out = new float[size];
            int index = 0;
            for (float[][] first : value) {
                for (float[] second : first) {
                    for (float sample : second) {
                        out[index++] = sample;
                    }
                }
            }
            return out;
        }
    }

    static final class Glue {
        final float[] bosBeforeVoice;
        final float[] latentMean;
        final float[] latentStd;
        final float[] speakerProjection;

        private Glue(float[] bosBeforeVoice, float[] latentMean, float[] latentStd, float[] speakerProjection) {
            this.bosBeforeVoice = bosBeforeVoice;
            this.latentMean = latentMean;
            this.latentStd = latentStd;
            this.speakerProjection = speakerProjection;
        }

        static Glue load(Path npz) throws Exception {
            try (ZipFile zip = new ZipFile(npz.toFile())) {
                return new Glue(
                        readNpy(zip, "bos_before_voice.npy"),
                        readNpy(zip, "emb_mean.npy"),
                        readNpy(zip, "emb_std.npy"),
                        readNpy(zip, "speaker_proj.npy"));
            }
        }

        private static float[] readNpy(ZipFile zip, String name) throws Exception {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().equals(name)) {
                    continue;
                }
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                int headerLength = ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
                int dataOffset = 10 + headerLength;
                float[] values = new float[(bytes.length - dataOffset) / 4];
                ByteBuffer.wrap(bytes, dataOffset, values.length * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(values);
                return values;
            }
            throw new IllegalStateException("Missing Pocket glue tensor: " + name);
        }
    }

    static int frameSize() {
        return MIMI_FRAME_SIZE;
    }
}
