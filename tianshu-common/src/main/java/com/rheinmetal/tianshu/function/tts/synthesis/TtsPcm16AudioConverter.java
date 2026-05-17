package com.rheinmetal.tianshu.function.tts.synthesis;

public final class TtsPcm16AudioConverter {
    private TtsPcm16AudioConverter() {
    }

    public static byte[] fromMonoFloat(float[] samples) {
        if (samples == null || samples.length == 0) {
            return new byte[0];
        }
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            short value = clamped <= -1.0f
                    ? Short.MIN_VALUE
                    : (short) Math.round(clamped * 32767.0f);
            pcm[2 * i] = (byte) (value & 0xFF);
            pcm[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    public static byte[] fromChannels(float[][] channels) {
        return fromMonoFloat(downmixToMono(channels));
    }

    public static float[] downmixToMono(float[][] channels) {
        if (channels == null || channels.length == 0 || channels[0] == null || channels[0].length == 0) {
            return new float[0];
        }
        if (channels.length == 1) {
            return channels[0];
        }
        int length = Integer.MAX_VALUE;
        int channelCount = 0;
        for (float[] channel : channels) {
            if (channel != null && channel.length > 0) {
                length = Math.min(length, channel.length);
                channelCount++;
            }
        }
        if (channelCount == 0 || length == Integer.MAX_VALUE) {
            return new float[0];
        }
        float[] mono = new float[length];
        for (int i = 0; i < length; i++) {
            float sum = 0.0f;
            for (float[] channel : channels) {
                if (channel != null && channel.length > i) {
                    sum += channel[i];
                }
            }
            mono[i] = sum / channelCount;
        }
        return mono;
    }
}
