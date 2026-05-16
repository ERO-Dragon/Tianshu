package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsVoiceProfile(
        String voiceStyle,
        float speed,
        int speakerId,
        String voiceSample
) {
    public TtsVoiceProfile {
        voiceStyle = voiceStyle == null ? "" : voiceStyle.trim();
        speed = speed <= 0.0f ? 1.0f : Math.max(0.1f, Math.min(5.0f, speed));
        speakerId = Math.max(0, speakerId);
        voiceSample = voiceSample == null ? "" : voiceSample.trim();
    }

    public static TtsVoiceProfile defaults() {
        return new TtsVoiceProfile("", 1.0f, 0, "");
    }
}
