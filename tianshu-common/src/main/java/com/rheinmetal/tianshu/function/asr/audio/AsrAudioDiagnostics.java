package com.rheinmetal.tianshu.function.asr.audio;

import java.util.Arrays;

/**
 * Bounded, opt-in audio observations for diagnosing the ASR VAD.
 * It stores waveform envelopes and threshold history, never PCM bytes.
 */
public final class AsrAudioDiagnostics {
    /** One streaming audio callback at the ASR bridge's 16 kHz/100 ms frame size. */
    public static final int BIN_SAMPLES = 1600;
    /** Five seconds of bounded history at one observation per 100 ms callback. */
    public static final int HISTORY_BINS = 50;

    private final float[] rawMin = new float[HISTORY_BINS];
    private final float[] rawMax = new float[HISTORY_BINS];
    private final float[] processedMin = new float[HISTORY_BINS];
    private final float[] processedMax = new float[HISTORY_BINS];
    private final float[] rawRmsHistory = new float[HISTORY_BINS];
    private final float[] processedRmsHistory = new float[HISTORY_BINS];
    private final float[] startThresholdHistory = new float[HISTORY_BINS];
    private final float[] stopThresholdHistory = new float[HISTORY_BINS];
    private volatile boolean enabled;
    private boolean captureActive;
    private long sessionId;
    private int size;
    private int writeIndex;
    private AsrSpeechActivitySnapshot latestVad = AsrSpeechActivitySnapshot.inactive();

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        clearHistory();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void beginCapture(long sessionId) {
        captureActive = true;
        this.sessionId = Math.max(0L, sessionId);
        if (enabled) {
            clearHistory();
        }
    }

    public synchronized void endCapture() {
        captureActive = false;
        latestVad = new AsrSpeechActivitySnapshot(
                latestVad.rms(),
                latestVad.startThreshold(),
                latestVad.stopThreshold(),
                false,
                latestVad.sessionId(),
                Math.max(latestVad.occurredAtMillis(), System.currentTimeMillis())
        );
    }

    public synchronized void record(byte[] rawPcm, byte[] processedPcm, AsrSpeechActivitySnapshot vad) {
        if (!enabled || !captureActive) {
            return;
        }
        float[] rawEnvelope = envelope(rawPcm);
        float[] processedEnvelope = envelope(processedPcm);
        int index = writeIndex;
        rawMin[index] = rawEnvelope[0];
        rawMax[index] = rawEnvelope[1];
        processedMin[index] = processedEnvelope[0];
        processedMax[index] = processedEnvelope[1];
        rawRmsHistory[index] = rms(rawPcm);
        processedRmsHistory[index] = rms(processedPcm);
        AsrSpeechActivitySnapshot currentVad = vad == null ? AsrSpeechActivitySnapshot.inactive() : vad;
        startThresholdHistory[index] = (float) currentVad.startThreshold();
        stopThresholdHistory[index] = (float) currentVad.stopThreshold();
        writeIndex = (writeIndex + 1) % HISTORY_BINS;
        size = Math.min(HISTORY_BINS, size + 1);
        latestVad = currentVad;
    }

    public synchronized Snapshot snapshot() {
        if (!enabled) {
            return Snapshot.empty();
        }
        float[] orderedRawMin = ordered(rawMin);
        float[] orderedRawMax = ordered(rawMax);
        float[] orderedProcessedMin = ordered(processedMin);
        float[] orderedProcessedMax = ordered(processedMax);
        float[] orderedRawRms = ordered(rawRmsHistory);
        float[] orderedProcessedRms = ordered(processedRmsHistory);
        float[] orderedStartThresholds = ordered(startThresholdHistory);
        float[] orderedStopThresholds = ordered(stopThresholdHistory);
        return new Snapshot(
                orderedRawMin,
                orderedRawMax,
                orderedProcessedMin,
                orderedProcessedMax,
                orderedRawRms,
                orderedProcessedRms,
                orderedStartThresholds,
                orderedStopThresholds,
                size,
                latestVad.rms(),
                latestVad.startThreshold(),
                latestVad.stopThreshold(),
                latestVad.speaking(),
                sessionId > 0L ? sessionId : latestVad.sessionId(),
                captureActive,
                latestVad.occurredAtMillis()
        );
    }

    private void clearHistory() {
        Arrays.fill(rawMin, 0.0F);
        Arrays.fill(rawMax, 0.0F);
        Arrays.fill(processedMin, 0.0F);
        Arrays.fill(processedMax, 0.0F);
        Arrays.fill(rawRmsHistory, 0.0F);
        Arrays.fill(processedRmsHistory, 0.0F);
        Arrays.fill(startThresholdHistory, 0.0F);
        Arrays.fill(stopThresholdHistory, 0.0F);
        size = 0;
        writeIndex = 0;
        latestVad = AsrSpeechActivitySnapshot.inactive();
    }

    private float[] ordered(float[] source) {
        float[] result = new float[size];
        int first = (writeIndex - size + HISTORY_BINS) % HISTORY_BINS;
        for (int index = 0; index < size; index++) {
            result[index] = source[(first + index) % HISTORY_BINS];
        }
        return result;
    }

    private static float[] envelope(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return new float[]{0.0F, 0.0F};
        }
        int sampleBytes = pcm.length - pcm.length % 2;
        float min = 1.0F;
        float max = -1.0F;
        for (int index = 0; index < sampleBytes; index += 2) {
            short sample = (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8));
            float value = sample / 32768.0F;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new float[]{min, max};
    }

    private static float rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return 0.0F;
        }
        int sampleBytes = pcm.length - pcm.length % 2;
        int samples = sampleBytes / 2;
        double sumSquares = 0.0D;
        for (int index = 0; index < sampleBytes; index += 2) {
            short sample = (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8));
            double normalized = sample / 32768.0D;
            sumSquares += normalized * normalized;
        }
        return (float) Math.sqrt(sumSquares / Math.max(1, samples));
    }

    public record Snapshot(
            float[] rawMin,
            float[] rawMax,
            float[] processedMin,
            float[] processedMax,
            float[] rawRmsHistory,
            float[] processedRmsHistory,
            float[] startThresholds,
            float[] stopThresholds,
            int sampleCount,
            double rms,
            double startThreshold,
            double stopThreshold,
            boolean speaking,
            long sessionId,
            boolean captureActive,
            long occurredAtMillis
    ) {
        public Snapshot {
            rawMin = rawMin == null ? new float[0] : rawMin.clone();
            rawMax = rawMax == null ? new float[0] : rawMax.clone();
            processedMin = processedMin == null ? new float[0] : processedMin.clone();
            processedMax = processedMax == null ? new float[0] : processedMax.clone();
            rawRmsHistory = rawRmsHistory == null ? new float[0] : rawRmsHistory.clone();
            processedRmsHistory = processedRmsHistory == null ? new float[0] : processedRmsHistory.clone();
            startThresholds = startThresholds == null ? new float[0] : startThresholds.clone();
            stopThresholds = stopThresholds == null ? new float[0] : stopThresholds.clone();
            sampleCount = Math.max(0, Math.min(sampleCount,
                    Math.min(
                            Math.min(Math.min(rawMin.length, rawMax.length), Math.min(processedMin.length, processedMax.length)),
                            Math.min(Math.min(startThresholds.length, stopThresholds.length),
                                    Math.min(rawRmsHistory.length, processedRmsHistory.length))
                    )));
            rms = finiteNonNegative(rms);
            startThreshold = finiteNonNegative(startThreshold);
            stopThreshold = Math.min(startThreshold, finiteNonNegative(stopThreshold));
            sessionId = Math.max(0L, sessionId);
            occurredAtMillis = Math.max(0L, occurredAtMillis);
        }

        public static Snapshot empty() {
            return new Snapshot(new float[0], new float[0], new float[0], new float[0], new float[0], new float[0], new float[0], new float[0],
                    0, 0.0D, 0.0D, 0.0D, false, 0L, false, 0L);
        }

        public double rawRms() {
            return latest(rawRmsHistory);
        }

        public double processedRms() {
            return latest(processedRmsHistory);
        }

        private static double latest(float[] values) {
            return values.length == 0 ? 0.0D : Math.max(0.0D, values[values.length - 1]);
        }

        private static double finiteNonNegative(double value) {
            return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
        }
    }
}
