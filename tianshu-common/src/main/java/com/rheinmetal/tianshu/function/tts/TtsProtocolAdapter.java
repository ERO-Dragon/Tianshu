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
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsAudioPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsAudioAckPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;

public final class TtsProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.tts";
    public static final String SOURCE_ID = "module.tts";
    private static final long MODEL_LOADING_TTL_MILLIS = 300_000L;
    private static final long REQUEST_ACTIVITY_TTL_MILLIS = 900_000L;

    public TtsProtocolAdapter(ModuleRuntimeAccess runtime) {
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

    public void registerSynthesizeCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_SYNTHESIZE,
                PayloadType.TTS_TEXT,
                TtsSynthesisRequestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.REQUEST),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void registerAudioAckCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_AUDIO_ACK,
                PayloadType.TTS_AUDIO_ACK,
                TtsAudioAckPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.NORMAL,
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

    public TianshuEnvelope publishPlaybackStatus(TtsPlaybackStatusPayload payload) {
        return publishTopic(ProtocolTopics.TTS_PLAYBACK, PayloadType.TTS_PLAYBACK_STATUS, payload);
    }

    public TianshuEnvelope publishRequestStatus(TtsRequestStatusPayload payload) {
        return publishTopic(ProtocolTopics.TTS_REQUEST_STATUS, PayloadType.TTS_REQUEST_STATUS, payload);
    }

    public TianshuEnvelope publishLoadingActivity(boolean loading) {
        return publishPresenceActivity(loading
                ? PresenceActivityPayload.started("tts.model.load", PresenceActivityType.LOADING, MODEL_LOADING_TTL_MILLIS)
                : PresenceActivityPayload.ended("tts.model.load", PresenceActivityType.LOADING));
    }

    public TianshuEnvelope publishRequestActivity(String requestId, boolean active) {
        String activityId = "tts.request." + requestId;
        return publishPresenceActivity(active
                ? PresenceActivityPayload.started(activityId, PresenceActivityType.PROCESSING_TASK, REQUEST_ACTIVITY_TTL_MILLIS)
                : PresenceActivityPayload.ended(activityId, PresenceActivityType.PROCESSING_TASK));
    }

    private TianshuEnvelope publishPresenceActivity(PresenceActivityPayload payload) {
        return publishTopic(ProtocolTopics.PRESENCE_ACTIVITY, PayloadType.PRESENCE_ACTIVITY, payload);
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public TianshuEnvelope respondAudio(TianshuEnvelope parent, TtsAudioPayload payload) {
        return respondTo(parent, PayloadType.TTS_AUDIO, payload);
    }
}


