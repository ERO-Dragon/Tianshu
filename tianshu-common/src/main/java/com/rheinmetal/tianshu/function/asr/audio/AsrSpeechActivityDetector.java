package com.rheinmetal.tianshu.function.asr.audio;

public final class AsrSpeechActivityDetector {
    private static final int BYTES_PER_SAMPLE = 2;
    private static final double DEFAULT_START_RMS = 0.012D;
    private static final double DEFAULT_STOP_RMS = 0.007D;
    private static final int DEFAULT_ACTIVE_FRAMES_TO_START = 1;
    private static final int DEFAULT_SILENT_FRAMES_TO_STOP = 6;

    private final double startRms;
    private final double stopRms;
    private final int activeFramesToStart;
    private final int silentFramesToStop;
    private final AsrSpeechActivityListener listener;
    private boolean speaking;
    private int activeFrames;
    private int silentFrames;
    private long sessionId;

    public AsrSpeechActivityDetector(AsrSpeechActivityListener listener) {
        this(DEFAULT_START_RMS, DEFAULT_STOP_RMS, DEFAULT_ACTIVE_FRAMES_TO_START, DEFAULT_SILENT_FRAMES_TO_STOP, listener);
    }

    AsrSpeechActivityDetector(double startRms, double stopRms, int activeFramesToStart, int silentFramesToStop, AsrSpeechActivityListener listener) {
        this.startRms = Math.max(0.0D, startRms);
        this.stopRms = Math.max(0.0D, Math.min(this.startRms, stopRms));
        this.activeFramesToStart = Math.max(1, activeFramesToStart);
        this.silentFramesToStop = Math.max(1, silentFramesToStop);
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
        if (!speaking) {
            if (rms >= startRms) {
                activeFrames++;
                if (activeFrames >= activeFramesToStart) {
                    speaking = true;
                    silentFrames = 0;
                    listener.onSpeechActivity(true, sessionId, System.currentTimeMillis());
                }
            } else {
                activeFrames = 0;
            }
            return;
        }
        if (rms <= stopRms) {
            silentFrames++;
            if (silentFrames >= silentFramesToStop) {
                speaking = false;
                activeFrames = 0;
                listener.onSpeechActivity(false, sessionId, System.currentTimeMillis());
            }
        } else {
            silentFrames = 0;
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
        activeFrames = 0;
        silentFrames = 0;
        sessionId = 0L;
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
}
