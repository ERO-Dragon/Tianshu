package com.rheinmetal.tianshu.function.tts.runtime;

public record TtsVoiceProfile(
        String voiceId,
        float speed,
        int speakerId,
        String voiceSample,
        float[] referenceAudio,
        int referenceSampleRate,
        String referenceText
) {
    public TtsVoiceProfile(String voiceId, float speed, int speakerId, String voiceSample) {
        this(voiceId, speed, speakerId, voiceSample, new float[0], 1, "");
    }

    public TtsVoiceProfile {
        voiceId = voiceId == null ? "" : voiceId.trim();
        speed = speed <= 0.0f ? 1.0f : Math.max(0.1f, Math.min(5.0f, speed));
        speakerId = Math.max(0, speakerId);
        voiceSample = voiceSample == null ? "" : voiceSample.trim();
        referenceAudio = referenceAudio == null ? new float[0] : referenceAudio.clone();
        referenceSampleRate = Math.max(1, referenceSampleRate);
        referenceText = referenceText == null ? "" : referenceText.trim();
    }

    public static TtsVoiceProfile defaults() {
        return new TtsVoiceProfile("", 1.0f, 0, "", new float[0], 1, "");
    }

    @Override
    public float[] referenceAudio() {
        return referenceAudio.clone();
    }
}
