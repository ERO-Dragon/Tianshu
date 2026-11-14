package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.function.echo.protocol.TtsCapabilities;

import com.rheinmetal.tianshu.function.echo.bridge.TtsPresenceActivityBridge;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.echo.protocol.TtsTopics;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioAckPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatusPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;

public final class TtsProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = TtsModuleId.VALUE;
    public static final String SOURCE_ID = TtsModuleId.VALUE;
    private static final long MODEL_LOADING_TTL_MILLIS = 300_000L;
    private static final long REQUEST_ACTIVITY_TTL_MILLIS = 900_000L;

    /**
     * Registers the playback and request-status topics this module produces.
     *
     * <p>Topic ownership follows payload ownership: the producer declares its own route. The kernel used to
     * register these for the four original modules, so deleting a module still left its vocabulary behind.</p>
     */
    public void registerOwnedTopics(com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess protocol) {
        if (protocol == null) {
            return;
        }
        protocol.registerTopic(new com.rheinmetal.tianshu.protocol.registry.TopicDescriptor(
                TtsTopics.TTS_PLAYBACK, PayloadType.of("TTS_PLAYBACK_STATUS"),
                com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE, 20));
        protocol.registerTopic(new com.rheinmetal.tianshu.protocol.registry.TopicDescriptor(
                TtsTopics.TTS_REQUEST_STATUS, PayloadType.of("TTS_REQUEST_STATUS"),
                com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE, 64));
    }

    private final TtsPresenceActivityBridge presenceActivity;

    public TtsProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withConcurrency(1, 64));
        this.presenceActivity = TtsPresenceActivityBridge.of(runtime, SOURCE_ID);
    }

    public void registerSpeakCapability(EnvelopeHandler handler) {
        registerCapability(
                TtsCapabilities.TTS_SPEAK,
                PayloadType.of("TTS_TEXT"),
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
                TtsCapabilities.TTS_SYNTHESIZE,
                PayloadType.of("TTS_TEXT"),
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
                TtsCapabilities.TTS_AUDIO_ACK,
                PayloadType.of("TTS_AUDIO_ACK"),
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
                TtsCapabilities.TTS_CONTROL,
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
        return publishTopic(TtsTopics.TTS_PLAYBACK, PayloadType.of("TTS_PLAYBACK_STATUS"), payload);
    }

    public TianshuEnvelope publishRequestStatus(TtsRequestStatusPayload payload) {
        return publishTopic(TtsTopics.TTS_REQUEST_STATUS, PayloadType.of("TTS_REQUEST_STATUS"), payload);
    }

    public TianshuEnvelope publishLoadingActivity(boolean loading) {
        return presenceActivity.publish(
                "tts.model.load",
                TtsPresenceActivityBridge.Kind.LOADING,
                loading,
                MODEL_LOADING_TTL_MILLIS
        );
    }

    public TianshuEnvelope publishRequestActivity(String requestId, boolean active) {
        return presenceActivity.publish(
                "tts.request." + requestId,
                TtsPresenceActivityBridge.Kind.PROCESSING_TASK,
                active,
                REQUEST_ACTIVITY_TTL_MILLIS
        );
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public TianshuEnvelope respondAudio(TianshuEnvelope parent, TtsAudioPayload payload) {
        return respondTo(parent, PayloadType.of("TTS_AUDIO"), payload);
    }
}


