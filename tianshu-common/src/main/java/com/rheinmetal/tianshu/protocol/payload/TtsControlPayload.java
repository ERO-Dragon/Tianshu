package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.Arrays;

public final class TtsControlPayload implements ITianshuPayload {
    public enum Action {
        STOP_CURRENT,
        STOP,
        STOP_SOURCE,
        RELOAD_MODEL,
        LOAD_VOICE,
        IMPORT_VOICE,
        UNLOAD_VOICE,
        CLEAR_VOICE_CACHE
    }

    private final Action action;
    private final String targetRequestId;
    private final String targetSource;
    private final String reason;
    private final String voiceId;
    private final String voiceSample;
    private final String referenceText;
    private final byte[] voiceAudio;

    public TtsControlPayload(
            Action action,
            String targetRequestId,
            String targetSource,
            String reason,
            String voiceId,
            String voiceSample,
            String referenceText
    ) {
        this(action, targetRequestId, targetSource, reason, voiceId, voiceSample, referenceText, null);
    }

    public TtsControlPayload(
            Action action,
            String targetRequestId,
            String targetSource,
            String reason,
            String voiceId,
            String voiceSample,
            String referenceText,
            byte[] voiceAudio
    ) {
        this.action = action == null ? Action.STOP : action;
        this.targetRequestId = normalize(targetRequestId);
        this.targetSource = normalize(targetSource);
        this.reason = normalize(reason);
        this.voiceId = normalize(voiceId);
        this.voiceSample = normalize(voiceSample);
        this.referenceText = normalize(referenceText);
        this.voiceAudio = voiceAudio == null ? new byte[0] : Arrays.copyOf(voiceAudio, voiceAudio.length);
    }

    public TtsControlPayload(Action action, String targetRequestId, String targetSource, String reason) {
        this(action, targetRequestId, targetSource, reason, "", "", "");
    }

    public TtsControlPayload(Action action, String targetRequestId, String reason) {
        this(action, targetRequestId, "", reason, "", "", "");
    }

    public TtsControlPayload(Action action, String voiceId, String voiceSample, String referenceText, String reason) {
        this(action, "", "", reason, voiceId, voiceSample, referenceText);
    }

    public TtsControlPayload(Action action, String voiceId, byte[] voiceAudio) {
        this(action, "", "", "", voiceId, "", "", voiceAudio);
    }

    public TtsControlPayload(Action action, String voiceId, byte[] voiceAudio, String referenceText) {
        this(action, "", "", "", voiceId, "", referenceText, voiceAudio);
    }

    public Action action() {
        return action;
    }

    public String targetRequestId() {
        return targetRequestId;
    }

    public String targetSource() {
        return targetSource;
    }

    public String reason() {
        return reason;
    }

    public String voiceId() {
        return voiceId;
    }

    public String voiceSample() {
        return voiceSample;
    }

    public String referenceText() {
        return referenceText;
    }

    public byte[] voiceAudio() {
        return Arrays.copyOf(voiceAudio, voiceAudio.length);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
