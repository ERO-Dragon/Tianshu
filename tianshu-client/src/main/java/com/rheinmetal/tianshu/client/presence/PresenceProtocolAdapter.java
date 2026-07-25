package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceChatMessagePayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.EnumSet;

public final class PresenceProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.presence";
    public static final String SOURCE_ID = "module.presence";

    private boolean ownedTopicsRegistered;

    public PresenceProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public synchronized void registerOwnedTopics(ModuleProtocolAccess protocol) {
        if (ownedTopicsRegistered) {
            return;
        }
        protocol.registerTopic(new TopicDescriptor(
                PresenceWorldEventPayload.TOPIC,
                PayloadType.CUSTOM,
                DeliveryPolicy.WAIT_IN_QUEUE,
                40
        ));
        protocol.registerTopic(new TopicDescriptor(
                PresenceChatMessagePayload.TOPIC,
                PayloadType.CUSTOM,
                DeliveryPolicy.WAIT_IN_QUEUE,
                80
        ));
        ownedTopicsRegistered = true;
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

    public void subscribePresenceActivity(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.PRESENCE_ACTIVITY,
                PayloadType.PRESENCE_ACTIVITY,
                PresenceActivityPayload.class,
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

    public TianshuEnvelope publishWorldEvent(PresenceWorldEventPayload payload) {
        return publishTopic(PresenceWorldEventPayload.TOPIC, PayloadType.CUSTOM, payload);
    }

    public TianshuEnvelope publishChatMessage(PresenceChatMessagePayload payload) {
        return publishTopic(PresenceChatMessagePayload.TOPIC, PayloadType.CUSTOM, payload);
    }

}

