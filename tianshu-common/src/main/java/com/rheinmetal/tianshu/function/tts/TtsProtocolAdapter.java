package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class TtsProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.tts";
    public static final String SOURCE_ID = "module.tts";

    public TtsProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withConcurrency(1, 64));
    }

    public void registerSpeakCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_SPEAK,
                PayloadType.TTS_TEXT,
                TtsSpeakPayload.class,
                BrokerType.EXCLUSIVE_INTERRUPT,
                EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerAlertCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_ALERT,
                PayloadType.TTS_TEXT,
                TtsSpeakPayload.class,
                BrokerType.EXCLUSIVE_INTERRUPT,
                EnumSet.of(PacketType.COMMAND),
                Priority.HIGH,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerControlCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_CONTROL,
                PayloadType.CUSTOM,
                TtsControlPayload.class,
                BrokerType.LATEST_ONLY,
                EnumSet.of(PacketType.COMMAND, PacketType.REQUEST),
                Priority.CRITICAL,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void subscribeAsrSpeechActivity(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY,
                PayloadType.ASR_SPEECH_ACTIVITY,
                AsrSpeechActivityPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope publishPlaybackStatus(TtsPlaybackStatusPayload payload) {
        return publishTopic(ProtocolTopics.TTS_PLAYBACK, PayloadType.TTS_PLAYBACK_STATUS, payload);
    }
}
