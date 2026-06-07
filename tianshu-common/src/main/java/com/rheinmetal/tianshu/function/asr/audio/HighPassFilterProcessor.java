package com.rheinmetal.tianshu.function.asr.audio;

public final class HighPassFilterProcessor implements AudioFrameProcessor {
    private static final int BYTES_PER_SAMPLE = 2;

    private final double alpha;
    private double previousInput;
    private double previousOutput;

    public HighPassFilterProcessor(int sampleRate, double cutoffHz) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (cutoffHz <= 0.0D || cutoffHz >= sampleRate / 2.0D) {
            throw new IllegalArgumentException("cutoffHz must be between 0 and Nyquist frequency");
        }
        double rc = 1.0D / (2.0D * Math.PI * cutoffHz);
        double dt = 1.0D / sampleRate;
        this.alpha = rc / (rc + dt);
    }

    @Override
    public synchronized byte[] process(byte[] audio) {
        if (audio == null || audio.length < BYTES_PER_SAMPLE) {
            return audio;
        }
        byte[] filtered = new byte[audio.length];
        int sampleBytes = audio.length - audio.length % BYTES_PER_SAMPLE;
        for (int index = 0; index < sampleBytes; index += BYTES_PER_SAMPLE) {
            double input = readPcm16Le(audio, index) / 32768.0D;
            double output = alpha * (previousOutput + input - previousInput);
            previousInput = input;
            previousOutput = output;
            writePcm16Le(filtered, index, clampToPcm16(output));
        }
        if (sampleBytes < audio.length) {
            filtered[audio.length - 1] = audio[audio.length - 1];
        }
        return filtered;
    }

    @Override
    public synchronized void reset() {
        previousInput = 0.0D;
        previousOutput = 0.0D;
    }

    private short readPcm16Le(byte[] audio, int index) {
        return (short) ((audio[index] & 0xFF) | (audio[index + 1] << 8));
    }

    private short clampToPcm16(double sample) {
        int value = (int) Math.round(sample * 32768.0D);
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    private void writePcm16Le(byte[] audio, int index, short sample) {
        audio[index] = (byte) (sample & 0xFF);
        audio[index + 1] = (byte) ((sample >>> 8) & 0xFF);
    }
}
