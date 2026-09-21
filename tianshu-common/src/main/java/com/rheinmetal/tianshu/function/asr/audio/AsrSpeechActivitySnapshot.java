package com.rheinmetal.tianshu.function.asr.audio;

/** Immutable observation of the VAD after one processed audio chunk. */
public record AsrSpeechActivitySnapshot(
        double rms,
        double startThreshold,
        double stopThreshold,
        boolean speaking,
        long sessionId,
        long occurredAtMillis
) {
    public AsrSpeechActivitySnapshot {
        rms = finiteNonNegative(rms);
        startThreshold = finiteNonNegative(startThreshold);
        stopThreshold = Math.min(startThreshold, finiteNonNegative(stopThreshold));
        sessionId = Math.max(0L, sessionId);
        occurredAtMillis = Math.max(0L, occurredAtMillis);
    }

    public static AsrSpeechActivitySnapshot inactive() {
        return new AsrSpeechActivitySnapshot(0.0D, 0.0D, 0.0D, false, 0L, 0L);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }
}
