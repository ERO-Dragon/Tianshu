package com.rheinmetal.tianshu.function.tts.synthesis;

public record TtsSynthesisMetrics(
        TtsSynthesisMode mode,
        int textLength,
        long audioMillis,
        long synthesisMillis,
        long firstAudioMillis
) {
    public TtsSynthesisMetrics {
        mode = mode == null ? TtsSynthesisMode.FULL : mode;
        textLength = Math.max(0, textLength);
        audioMillis = Math.max(0L, audioMillis);
        synthesisMillis = Math.max(0L, synthesisMillis);
        firstAudioMillis = Math.max(0L, firstAudioMillis);
    }

    public double rtf() {
        if (audioMillis <= 0L) {
            return 0.0D;
        }
        return synthesisMillis / (double) audioMillis;
    }

    public long audioMillisPerCharacter() {
        if (textLength <= 0 || audioMillis <= 0L) {
            return 0L;
        }
        return Math.max(1L, audioMillis / textLength);
    }
}
