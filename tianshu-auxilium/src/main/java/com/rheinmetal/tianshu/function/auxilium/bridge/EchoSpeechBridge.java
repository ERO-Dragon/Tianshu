package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.function.auxilium.AXModule;

import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechUtterance;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.function.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsTextInputMode;
import com.rheinmetal.tianshu.function.echo.contract.TtsVoiceOptions;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;

/**
 * The only place auxilium touches the speech module's payload classes.
 *
 * <p>The speech module is an optional integration here, so this class is only instantiated when it is
 * installed ({@link AXSpeechSinks}). Every reference to a Tts* payload in this module belongs here; the
 * rest of the module works against {@link AXSpeechSink} and loads with or without that module.</p>
 */
public final class EchoSpeechBridge implements AXSpeechSink {

    private final ModuleProtocolAccess runtime;

    public EchoSpeechBridge(ModuleProtocolAccess runtime) {
        this.runtime = runtime;
    }

    @Override
    public void speak(AXSpeechUtterance utterance) {
        AXSpeechUtterance effective = utterance == null
                ? AXSpeechUtterance.command("", AXSpeechUtterance.Placement.AFTER_SESSION, Priority.NORMAL,
                        AXSpeechUtterance.VoiceProfile.defaults())
                : utterance;
        TtsSpeakPayload payload = new TtsSpeakPayload(
                effective.text(),
                effective.turnId(),
                effective.sessionId(),
                placementOf(effective.placement()),
                TtsTextInputMode.SENTENCE_STREAM,
                voiceOptionsOf(effective.voice()));
        TianshuEnvelope envelope = EnvelopeBuilder.commandToCapability(
                        AXModule.MODULE_ID,
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
                AXModule.MODULE_ID,
                reasonCode == null ? "" : reasonCode);
        runtime.submit(EnvelopeBuilder.commandToCapability(
                        AXModule.MODULE_ID,
                        "TTS_CONTROL",
                        PayloadType.CUSTOM,
                        payload)
                .build());
    }

    private static PacketType packetTypeOf(AXSpeechUtterance.Mode mode) {
        return switch (mode) {
            case COMMAND -> PacketType.COMMAND;
            case STREAM_CHUNK -> PacketType.STREAM_CHUNK;
            case STREAM_END -> PacketType.STREAM_END;
        };
    }

    private static TtsPlaybackPlacement placementOf(AXSpeechUtterance.Placement placement) {
        return switch (placement) {
            case AFTER_SENTENCE -> TtsPlaybackPlacement.INSERT_AFTER_SENTENCE;
            case AFTER_SESSION -> TtsPlaybackPlacement.INSERT_AFTER_SESSION;
            case QUEUE_AFTER_SESSION -> TtsPlaybackPlacement.QUEUE_AFTER_SESSION;
        };
    }

    private static TtsVoiceOptions voiceOptionsOf(AXSpeechUtterance.VoiceProfile voice) {
        AXSpeechUtterance.VoiceProfile effective = voice == null
                ? AXSpeechUtterance.VoiceProfile.defaults()
                : voice;
        String voiceSample = effective.voiceSample() == null ? "" : effective.voiceSample().trim();
        return new TtsVoiceOptions(voiceSample, (float) effective.speed(), effective.speakerId());
    }
}