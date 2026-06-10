package com.rheinmetal.tianshu.function.asr.audio;

public final class AsrSpeechActivityDetector {
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final double DEFAULT_MIN_START_RMS = 0.012D;
    private static final double DEFAULT_MIN_STOP_RMS = 0.005D;
    private static final double DEFAULT_NOISE_START_MULTIPLIER = 3.0D;
    private static final double DEFAULT_NOISE_STOP_MULTIPLIER = 1.5D;
    private static final double DEFAULT_INITIAL_NOISE_FLOOR = 0.002D;
    private static final double DEFAULT_MAX_START_THRESHOLD = 0.03D;
    private static final long DEFAULT_NOISE_UPDATE_COOLDOWN_MILLIS = 300L;
    private static final long DEFAULT_MIN_SPEAKING_MILLIS = 300L;
    private static final long DEFAULT_SHORT_SPEECH_MILLIS = 2000L;
    private static final long DEFAULT_MEDIUM_SPEECH_MILLIS = 5000L;
    private static final long DEFAULT_SHORT_SILENCE_TO_STOP_MILLIS = 450L;
    private static final long DEFAULT_MEDIUM_SILENCE_TO_STOP_MILLIS = 350L;
    private static final long DEFAULT_LONG_SILENCE_TO_STOP_MILLIS = 250L;

    private final int sampleRate;
    private final double minStartRms;
    private final double minStopRms;
    private final double noiseStartMultiplier;
    private final double noiseStopMultiplier;
    private final double maxStartThreshold;
    private final double maxNoiseFloor;
    private final long noiseUpdateCooldownMillis;
    private final long minSpeakingMillis;
    private final long shortSpeechMillis;
    private final long mediumSpeechMillis;
    private final long shortSilenceToStopMillis;
    private final long mediumSilenceToStopMillis;
    private final long longSilenceToStopMillis;
    private final AsrSpeechActivityListener listener;
    private boolean speaking;
    private double noiseFloor;
    private long speakingMillis;
    private long silentMillis;
    private long silentSinceLastSpeechMillis;
    private long sessionId;

    public AsrSpeechActivityDetector(AsrSpeechActivityListener listener) {
        this(
                DEFAULT_SAMPLE_RATE,
                DEFAULT_MIN_START_RMS,
                DEFAULT_MIN_STOP_RMS,
                DEFAULT_NOISE_START_MULTIPLIER,
                DEFAULT_NOISE_STOP_MULTIPLIER,
                DEFAULT_INITIAL_NOISE_FLOOR,
                DEFAULT_MAX_START_THRESHOLD,
                DEFAULT_NOISE_UPDATE_COOLDOWN_MILLIS,
                DEFAULT_MIN_SPEAKING_MILLIS,
                DEFAULT_SHORT_SPEECH_MILLIS,
                DEFAULT_MEDIUM_SPEECH_MILLIS,
                DEFAULT_SHORT_SILENCE_TO_STOP_MILLIS,
                DEFAULT_MEDIUM_SILENCE_TO_STOP_MILLIS,
                DEFAULT_LONG_SILENCE_TO_STOP_MILLIS,
                listener
        );
    }

    AsrSpeechActivityDetector(
            int sampleRate,
            double minStartRms,
            double minStopRms,
            double noiseStartMultiplier,
            double noiseStopMultiplier,
            double initialNoiseFloor,
            double maxStartThreshold,
            long noiseUpdateCooldownMillis,
            long minSpeakingMillis,
            long shortSpeechMillis,
            long mediumSpeechMillis,
            long shortSilenceToStopMillis,
            long mediumSilenceToStopMillis,
            long longSilenceToStopMillis,
            AsrSpeechActivityListener listener
    ) {
        this.sampleRate = Math.max(1, sampleRate);
        this.minStartRms = Math.max(0.0D, minStartRms);
        this.minStopRms = Math.max(0.0D, Math.min(this.minStartRms, minStopRms));
        this.noiseStartMultiplier = Math.max(1.0D, noiseStartMultiplier);
        this.noiseStopMultiplier = Math.max(1.0D, Math.min(this.noiseStartMultiplier, noiseStopMultiplier));
        this.maxStartThreshold = Math.max(this.minStartRms, maxStartThreshold);
        this.maxNoiseFloor = this.maxStartThreshold / this.noiseStartMultiplier;
        this.noiseFloor = clamp(initialNoiseFloor, 0.0D, this.maxNoiseFloor);
        this.noiseUpdateCooldownMillis = Math.max(0L, noiseUpdateCooldownMillis);
        this.minSpeakingMillis = Math.max(0L, minSpeakingMillis);
        this.shortSpeechMillis = Math.max(1L, shortSpeechMillis);
        this.mediumSpeechMillis = Math.max(this.shortSpeechMillis, mediumSpeechMillis);
        this.shortSilenceToStopMillis = Math.max(1L, shortSilenceToStopMillis);
        this.mediumSilenceToStopMillis = Math.max(1L, mediumSilenceToStopMillis);
        this.longSilenceToStopMillis = Math.max(1L, longSilenceToStopMillis);
        this.listener = listener == null ? AsrSpeechActivityListener.noop() : listener;
    }

    public synchronized void start(long sessionId) {
        reset(false);
        this.sessionId = Math.max(0L, sessionId);
    }

    public synchronized void accept(byte[] pcm16le) {
        if (pcm16le == null || pcm16le.length < BYTES_PER_SAMPLE || sessionId <= 0L) {
            return;
        }
        double rms = rms(pcm16le);
        long chunkMillis = chunkMillis(pcm16le);
        if (!speaking) {
            if (rms >= startThreshold()) {
                speaking = true;
                speakingMillis = chunkMillis;
                silentMillis = 0L;
                listener.onSpeechActivity(true, sessionId, System.currentTimeMillis());
                return;
            }
            silentSinceLastSpeechMillis += chunkMillis;
            if (silentSinceLastSpeechMillis >= noiseUpdateCooldownMillis) {
                updateNoiseFloor(rms);
            }
            return;
        }
        speakingMillis += chunkMillis;
        if (rms < stopThreshold()) {
            silentMillis += chunkMillis;
            if (speakingMillis >= minSpeakingMillis && silentMillis >= silenceToStopMillis()) {
                speaking = false;
                speakingMillis = 0L;
                silentMillis = 0L;
                silentSinceLastSpeechMillis = 0L;
                listener.onSpeechActivity(false, sessionId, System.currentTimeMillis());
            }
        } else {
            silentMillis = 0L;
        }
    }

    public synchronized void stop() {
        reset(true);
    }

    public synchronized boolean speaking() {
        return speaking;
    }

    private void reset(boolean notifyStop) {
        if (notifyStop && speaking && sessionId > 0L) {
            listener.onSpeechActivity(false, sessionId, System.currentTimeMillis());
        }
        speaking = false;
        speakingMillis = 0L;
        silentMillis = 0L;
        silentSinceLastSpeechMillis = 0L;
        sessionId = 0L;
    }

    private double startThreshold() {
        return Math.min(maxStartThreshold, Math.max(minStartRms, noiseFloor * noiseStartMultiplier));
    }

    private double stopThreshold() {
        return Math.min(startThreshold(), Math.max(minStopRms, noiseFloor * noiseStopMultiplier));
    }

    private long silenceToStopMillis() {
        if (speakingMillis < shortSpeechMillis) {
            return shortSilenceToStopMillis;
        }
        if (speakingMillis < mediumSpeechMillis) {
            return mediumSilenceToStopMillis;
        }
        return longSilenceToStopMillis;
    }

    private void updateNoiseFloor(double rms) {
        double alpha = rms > noiseFloor ? 0.02D : 0.12D;
        noiseFloor = clamp(noiseFloor + alpha * (rms - noiseFloor), 0.0D, maxNoiseFloor);
    }

    private long chunkMillis(byte[] audio) {
        int samples = Math.max(1, (audio.length - audio.length % BYTES_PER_SAMPLE) / BYTES_PER_SAMPLE);
        return Math.max(1L, Math.round(samples * 1000.0D / sampleRate));
    }

    private static double rms(byte[] audio) {
        int sampleBytes = audio.length - audio.length % BYTES_PER_SAMPLE;
        if (sampleBytes <= 0) {
            return 0.0D;
        }
        double sumSquares = 0.0D;
        int samples = sampleBytes / BYTES_PER_SAMPLE;
        for (int index = 0; index < sampleBytes; index += BYTES_PER_SAMPLE) {
            short sample = (short) ((audio[index] & 0xFF) | (audio[index + 1] << 8));
            double normalized = sample / 32768.0D;
            sumSquares += normalized * normalized;
        }
        return Math.sqrt(sumSquares / samples);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
