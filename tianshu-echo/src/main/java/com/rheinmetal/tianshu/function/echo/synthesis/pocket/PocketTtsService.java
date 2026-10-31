package com.rheinmetal.tianshu.function.echo.synthesis.pocket;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.sentencepiece.SentencePieceProcessor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Pocket-TTS-ZH-4L synthesis: text front-end, voice prompt encoding, FlowLM generation and Mimi
 * decode. The backend-facing surface deliberately returns one mono waveform; streaming is added on
 * top of the same runtime rather than duplicating the model assembly.
 */
final class PocketTtsService implements AutoCloseable {
    private static final int MIN_FRAMES_BEFORE_EOS = 6;
    private static final int FRAMES_AFTER_EOS = 5;
    private static final int DECODE_BATCH_FRAMES = 4;
    private static final int SAMPLE_RATE = PocketModelRuntime.SAMPLE_RATE;

    private final PocketModelRuntime runtime;
    private final SentencePieceProcessor tokenizer;

    PocketTtsService(IGameEnvironment env, Path modelDir, InferenceResourcePolicy policy) throws Exception {
        this.runtime = new PocketModelRuntime(env, modelDir, policy);
        runtime.initialize();
        this.tokenizer = runtime.tokenizer();
    }

    int sampleRate() {
        return SAMPLE_RATE;
    }

    float[][] synthesizeToWaveform(String text, Path voiceSamplePath, BooleanSupplier cancelled) throws Exception {
        float[] voicePcm = readMono24k(voiceSamplePath);
        return synthesizeToWaveform(text, voicePcm, cancelled);
    }

    float[][] synthesizeToWaveform(String text, float[] referenceAudio, int referenceSampleRate, BooleanSupplier cancelled)
            throws Exception {
        float[] voicePcm = prepareReference(referenceAudio, referenceSampleRate);
        return synthesizeToWaveform(text, voicePcm, cancelled);
    }

    private float[][] synthesizeToWaveform(String text, float[] voicePcm, BooleanSupplier cancelled) throws Exception {
        if (text == null || text.isBlank()) {
            return new float[][]{new float[0]};
        }
        String normalized = PocketTextNormalizer.normalize(text);
        int[] textTokens = tokenizer.encode(normalized).stream().mapToInt(Integer::intValue).toArray();
        if (textTokens.length == 0) {
            return new float[][]{new float[0]};
        }

        float[][] voiceLatents = runtime.encodeVoice(padToMultiple(endOnPause(trimPrompt(voicePcm)), PocketModelRuntime.frameSize()));
        float[][] voiceConditioning = runtime.prependBos(runtime.projectVoice(voiceLatents));
        int capacity = voiceConditioning.length + textTokens.length + 512;
        PocketModelRuntime.State state = runtime.newState(capacity);
        runtime.prefill(voiceConditioning, state);
        runtime.prefill(runtime.conditioner(textTokens), state);

        int maxFrames = Math.max(32, (int) Math.ceil((textTokens.length / 3.0 + 2.0) * 12.5));
        List<float[]> latents = generate(state, maxFrames, cancelled);
        if (cancelled.getAsBoolean() || latents.isEmpty()) {
            return new float[][]{new float[0]};
        }
        float[] audio = runtime.decode(latents, DECODE_BATCH_FRAMES);
        return new float[][]{audio};
    }

    private List<float[]> generate(PocketModelRuntime.State state, int maxFrames, BooleanSupplier cancelled) throws Exception {
        List<float[]> latents = new ArrayList<>();
        Random random = new Random(ThreadLocalRandom.current().nextLong());
        float[] backbone = new float[PocketModelRuntime.LATENT_DIM];
        Arrays.fill(backbone, Float.NaN);
        int eosStep = -1;
        for (int step = 0; step < maxFrames; step++) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            float[] noise = new float[PocketModelRuntime.LATENT_DIM];
            for (int i = 0; i < noise.length; i++) {
                noise[i] = (float) (random.nextGaussian() * Math.sqrt(0.3));
            }
            PocketModelRuntime.Step output = runtime.step(backbone, noise, state);
            if (output.eos() && eosStep < 0 && step >= MIN_FRAMES_BEFORE_EOS) {
                eosStep = step;
            }
            if (eosStep >= 0 && step >= eosStep + FRAMES_AFTER_EOS) {
                break;
            }
            latents.add(output.latent());
            backbone = output.latent();
        }
        return latents;
    }

    private float[] prepareReference(float[] audio, int sourceRate) {
        float[] mono = audio.clone();
        if (sourceRate != SAMPLE_RATE) {
            mono = resample(mono, sourceRate, SAMPLE_RATE);
        }
        return mono;
    }

    private float[] readMono24k(Path wav) throws Exception {
        if (wav == null || !Files.isRegularFile(wav)) {
            throw new IllegalArgumentException("Pocket-TTS reference audio is missing: " + wav);
        }
        try (AudioInputStream input = AudioSystem.getAudioInputStream(new File(wav.toString()))) {
            AudioFormat format = input.getFormat();
            byte[] bytes = input.readAllBytes();
            int channels = format.getChannels();
            int bits = format.getSampleSizeInBits();
            int frameSize = format.getFrameSize();
            int frames = bytes.length / frameSize;
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                float sum = 0f;
                for (int channel = 0; channel < channels; channel++) {
                    int offset = i * frameSize + channel * (bits / 8);
                    short sample = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
                    sum += sample / 32768f;
                }
                mono[i] = sum / channels;
            }
            return prepareReference(mono, Math.round(format.getSampleRate()));
        }
    }

    private static float[] trimPrompt(float[] audio) {
        int window = 480;
        int windows = audio.length / window;
        int first = -1;
        int last = -1;
        for (int i = 0; i < windows; i++) {
            double sum = 0;
            for (int j = 0; j < window; j++) {
                float value = audio[i * window + j];
                sum += value * value;
            }
            if (Math.sqrt(sum / window) > 1e-3) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return audio;
        }
        int start = Math.max(0, (first - 1) * window);
        int end = Math.min(audio.length, (last + 1) * window);
        return Arrays.copyOfRange(audio, start, end);
    }

    private static float[] endOnPause(float[] audio) {
        int frame = 480;
        int windows = audio.length / frame;
        if (windows == 0) {
            return audio;
        }
        double[] decibels = new double[windows];
        double max = -1e9;
        for (int i = 0; i < windows; i++) {
            double sum = 0;
            for (int j = 0; j < frame; j++) {
                float value = audio[i * frame + j];
                sum += value * value;
            }
            decibels[i] = 20 * Math.log10(Math.sqrt(sum / frame) + 1e-12);
            max = Math.max(max, decibels[i]);
        }
        int last = 0;
        for (int i = 0; i < windows; i++) {
            if (decibels[i] > max - 35.0) {
                last = i;
            }
        }
        float[] trimmed = Arrays.copyOfRange(audio, 0, (last + 1) * frame);
        int fade = Math.min(480, trimmed.length);
        for (int i = 0; i < fade; i++) {
            trimmed[trimmed.length - fade + i] *= (fade - i) / (float) fade;
        }
        return trimmed;
    }

    private static float[] padToMultiple(float[] audio, int frame) {
        int extra = (frame - audio.length % frame) % frame;
        return extra == 0 ? audio : Arrays.copyOf(audio, audio.length + extra);
    }

    private static float[] resample(float[] input, int from, int to) {
        int gcd = gcd(from, to);
        int up = to / gcd;
        int down = from / gcd;
        if (up == 1 && down == 2) {
            return resamplePoly2(input);
        }
        int outputLength = (int) Math.round(input.length * (double) to / from);
        float[] output = new float[outputLength];
        double cutoff = 0.45 * Math.min(1.0, to / (double) from);
        int radius = 24;
        for (int n = 0; n < outputLength; n++) {
            double position = n * (double) down / up;
            int center = (int) Math.round(position);
            double sum = 0;
            double weight = 0;
            for (int k = -radius; k <= radius; k++) {
                int index = center + k;
                if (index < 0 || index >= input.length) {
                    continue;
                }
                double distance = index - position;
                double sinc = distance == 0 ? 1 : Math.sin(2 * Math.PI * cutoff * distance) / (2 * Math.PI * cutoff * distance);
                double w = 2 * cutoff * sinc * (0.54 + 0.46 * Math.cos(Math.PI * distance / (radius + 1)));
                sum += input[index] * w;
                weight += w;
            }
            output[n] = weight == 0 ? 0f : (float) (sum / weight);
        }
        return output;
    }

    private static float[] resamplePoly2(float[] input) {
        int half = 20;
        int taps = 2 * half + 1;
        double[] filter = new double[taps];
        double cutoff = 0.5;
        for (int i = 0; i < taps; i++) {
            int m = i - half;
            double sinc = m == 0 ? 1 : Math.sin(Math.PI * cutoff * m) / (Math.PI * cutoff * m);
            double ratio = m / (double) half;
            double kaiser = besselI0(5.0 * Math.sqrt(Math.max(0, 1 - ratio * ratio))) / besselI0(5.0);
            filter[i] = sinc * kaiser;
        }
        double sum = 0;
        for (double value : filter) {
            sum += value;
        }
        for (int i = 0; i < taps; i++) {
            filter[i] /= sum;
        }
        int outputLength = (input.length + 1) / 2;
        float[] output = new float[outputLength];
        for (int n = 0; n < outputLength; n++) {
            int center = n * 2;
            double value = 0;
            for (int k = 0; k < taps; k++) {
                int index = center + k - half;
                if (index >= 0 && index < input.length) {
                    value += input[index] * filter[k];
                }
            }
            output[n] = (float) value;
        }
        return output;
    }

    private static double besselI0(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 25; k++) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k));
            sum += term;
        }
        return sum;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int temp = a % b;
            a = b;
            b = temp;
        }
        return Math.max(1, a);
    }

    @Override
    public synchronized void close() {
        runtime.close();
    }
}
