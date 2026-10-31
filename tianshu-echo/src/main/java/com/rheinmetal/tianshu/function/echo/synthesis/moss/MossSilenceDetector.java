package com.rheinmetal.tianshu.function.echo.synthesis.moss;

/**
 * Recognises decoded MOSS audio that has nothing left in it.
 *
 * <p>The autoregressive loop can stop saying anything while its frames stay healthy: the codes keep
 * changing (so a repeated-frame check sees nothing) and they are ordinary codes (so a padding check
 * sees nothing), yet the waveform they decode to is silent. The tail then only ends at the frame cap.
 * Decoded energy is the one signal that covers that failure mode, so it is what both the streaming
 * decoder and the complete-result path watch.</p>
 */
final class MossSilenceDetector {
    /** -60 dBFS: below this a window carries no audible speech. */
    static final double SILENT_RMS = 1e-3D;
    /** Window used to walk a waveform backwards when looking for a silent tail. */
    private static final int WINDOW_MILLIS = 20;

    private MossSilenceDetector() {
    }

    static boolean isSilent(float[][] audio) {
        return audio == null || audio.length == 0 || audio[0] == null || audio[0].length == 0
                || rms(audio, 0, audio[0].length) < SILENT_RMS;
    }

    /**
     * @return how many samples at the end of the waveform are silent, walking back in 20 ms windows
     */
    static int trailingSilentSamples(float[][] audio, int sampleRate) {
        if (audio == null || audio.length == 0 || audio[0] == null || sampleRate <= 0) {
            return 0;
        }
        int samples = audio[0].length;
        int window = Math.max(1, sampleRate * WINDOW_MILLIS / 1000);
        int silent = 0;
        while (silent < samples) {
            int from = Math.max(0, samples - silent - window);
            if (rms(audio, from, samples - silent) >= SILENT_RMS) {
                break;
            }
            silent = samples - from;
        }
        return silent;
    }

    /**
     * Finds where the last long silence run starts.
     *
     * <p>A generation that hit the frame cap is not necessarily silent at the very end: the loop can
     * go quiet for many seconds and then emit noise again. Cutting at the start of the last sustained
     * silence keeps the speech that came before it and throws away everything after.</p>
     *
     * @return sample index where that run starts, or {@code -1} when no such run exists
     */
    static int lastLongSilenceStart(float[][] audio, int sampleRate, int minSilenceMillis) {
        if (audio == null || audio.length == 0 || audio[0] == null || sampleRate <= 0) {
            return -1;
        }
        int samples = audio[0].length;
        int window = Math.max(1, sampleRate * WINDOW_MILLIS / 1000);
        int neededWindows = Math.max(1, minSilenceMillis / WINDOW_MILLIS);
        int runStart = -1;
        int runWindows = 0;
        int found = -1;
        for (int start = 0; start + window <= samples; start += window) {
            if (rms(audio, start, start + window) < SILENT_RMS) {
                if (runStart < 0) {
                    runStart = start;
                }
                runWindows++;
                if (runWindows >= neededWindows) {
                    found = runStart;
                }
            } else {
                runStart = -1;
                runWindows = 0;
            }
        }
        return found;
    }

    private static double rms(float[][] audio, int from, int to) {
        double sum = 0.0D;
        long count = 0L;
        for (float[] channel : audio) {
            if (channel == null) {
                continue;
            }
            for (int index = Math.max(0, from); index < Math.min(to, channel.length); index++) {
                sum += (double) channel[index] * channel[index];
                count++;
            }
        }
        return count == 0L ? 0.0D : Math.sqrt(sum / count);
    }
}
