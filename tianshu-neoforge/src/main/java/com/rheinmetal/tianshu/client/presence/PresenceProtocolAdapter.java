package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class PresenceProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.presence";
    public static final String SOURCE_ID = "module.presence";

    public PresenceProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public void registerQueryContextCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.PRESENCE_QUERY_CONTEXT,
                PayloadType.PRESENCE_CONTEXT_QUERY,
                PresenceContextQueryPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.REQUEST),
                Priority.LOW,
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

    public void subscribeLlmStatus(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_STATUS,
                PayloadType.LLM_STATUS,
                LlmStatusPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribeTtsPlayback(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.TTS_PLAYBACK,
                PayloadType.TTS_PLAYBACK_STATUS,
                TtsPlaybackStatusPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribeModuleStatus(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.MODULE_STATUS,
                PayloadType.MODULE_STATUS,
                ModuleStatusPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope respondContext(TianshuEnvelope parent, PresenceContextSnapshotPayload payload) {
        return respondTo(parent, PayloadType.PRESENCE_CONTEXT_SNAPSHOT, payload);
    }

}

