package com.rheinmetal.tianshu.protocol.payload;

public record TtsVoiceOptions(
        String voiceId,
        Float speed,
        Integer speakerId
) {
    private static final float MIN_SPEED = 0.1F;
    private static final float MAX_SPEED = 5.0F;

    public TtsVoiceOptions {
        voiceId = voiceId == null ? "" : voiceId.trim();
        if (speed != null && (speed < MIN_SPEED || speed > MAX_SPEED || !Float.isFinite(speed))) {
            throw new IllegalArgumentException("TTS speed override must be between 0.1 and 5.0");
        }
        if (speakerId != null && speakerId < 0) {
            throw new IllegalArgumentException("TTS speaker override cannot be negative");
        }
    }

    public static TtsVoiceOptions defaults() {
        return new TtsVoiceOptions("", null, null);
    }
}
