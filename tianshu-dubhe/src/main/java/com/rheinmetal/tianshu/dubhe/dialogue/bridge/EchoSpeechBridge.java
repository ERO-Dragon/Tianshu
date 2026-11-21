package com.rheinmetal.tianshu.dubhe.dialogue.bridge;

import com.rheinmetal.tianshu.dubhe.DubheModule;

import com.rheinmetal.tianshu.dubhe.dialogue.speech.DubheSpeechSink;
import com.rheinmetal.tianshu.dubhe.dialogue.speech.DubheSpeechUtterance;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.echo.contract.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.echo.contract.TtsTextInputMode;
import com.rheinmetal.tianshu.echo.contract.TtsVoiceOptions;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;

/**
 * The only place dubhe touches the speech module's payload classes.
 *
 * <p>The speech module is an optional integration here, so this class is only instantiated when it is
 * installed ({@link DubheSpeechSinks}). Every reference to a Tts* payload in this module belongs here; the
 * rest of the module works against {@link DubheSpeechSink} and loads with or without that module.</p>
 */
public final class EchoSpeechBridge implements DubheSpeechSink {

    private final ModuleProtocolAccess runtime;

    public EchoSpeechBridge(ModuleProtocolAccess runtime) {
        this.runtime = runtime;
    }

    @Override
    public void speak(DubheSpeechUtterance utterance) {
        DubheSpeechUtterance effective = utterance == null
                ? DubheSpeechUtterance.command("", DubheSpeechUtterance.Placement.AFTER_SESSION, Priority.NORMAL,
                        DubheSpeechUtterance.VoiceProfile.defaults())
                : utterance;
        TtsSpeakPayload payload = new TtsSpeakPayload(
                effective.text(),
                effective.turnId(),
                effective.sessionId(),
                placementOf(effective.placement()),
                TtsTextInputMode.SENTENCE_STREAM,
                voiceOptionsOf(effective.voice()));
        TianshuEnvelope envelope = EnvelopeBuilder.commandToCapability(
                        DubheModule.MODULE_ID,
                        "TTS_SPEAK",
                        PayloadType.of("TTS_TEXT"),
                        payload)
                .packetType(packetTypeOf(effective.mode()))
                .priority(effective.priority())
                .build();
        runtime.submit(envelope);
    }

    @Override
    public void stopSource(String reasonCode) {
        TtsControlPayload payload = new TtsControlPayload(
                TtsControlPayload.Action.STOP_SOURCE,
                "",
                DubheModule.MODULE_ID,
                reasonCode == null ? "" : reasonCode);
        runtime.submit(EnvelopeBuilder.commandToCapability(
                        DubheModule.MODULE_ID,
                        "TTS_CONTROL",
                        PayloadType.CUSTOM,
                        payload)
                .build());
    }

    private static PacketType packetTypeOf(DubheSpeechUtterance.Mode mode) {
        return switch (mode) {
            case COMMAND -> PacketType.COMMAND;
            case STREAM_CHUNK -> PacketType.STREAM_CHUNK;
            case STREAM_END -> PacketType.STREAM_END;
        };
    }

    private static TtsPlaybackPlacement placementOf(DubheSpeechUtterance.Placement placement) {
        return switch (placement) {
            case AFTER_SENTENCE -> TtsPlaybackPlacement.INSERT_AFTER_SENTENCE;
            case AFTER_SESSION -> TtsPlaybackPlacement.INSERT_AFTER_SESSION;
            case QUEUE_AFTER_SESSION -> TtsPlaybackPlacement.QUEUE_AFTER_SESSION;
        };
    }

    private static TtsVoiceOptions voiceOptionsOf(DubheSpeechUtterance.VoiceProfile voice) {
        DubheSpeechUtterance.VoiceProfile effective = voice == null
                ? DubheSpeechUtterance.VoiceProfile.defaults()
                : voice;
        String voiceSample = effective.voiceSample() == null ? "" : effective.voiceSample().trim();
        return new TtsVoiceOptions(voiceSample, (float) effective.speed(), effective.speakerId());
    }
}