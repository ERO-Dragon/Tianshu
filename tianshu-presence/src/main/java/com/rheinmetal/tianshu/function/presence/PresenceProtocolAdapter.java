package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
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
import com.rheinmetal.tianshu.protocol.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.protocol.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;
import java.util.List;

public final class PresenceProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = ProtocolModuleIds.PRESENCE;
    public static final String SOURCE_ID = ProtocolModuleIds.PRESENCE;

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
                ProtocolTopics.PRESENCE_INTERACTION_CONTEXT,
                PayloadType.PRESENCE_INTERACTION_CONTEXT,
                DeliveryPolicy.LATEST_ONLY,
                20
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

    /**
     * Subscribes to the partner marker pushes the arbiter writes on presence's channel.
     *
     * <p>The topic and payload are presence's own vocabulary, so subscribing here is the same rule as owning
     * them: this module is the only consumer, and the writer never talks to the HUD directly.</p>
     */
    public void subscribePartner(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.PRESENCE_PARTNER,
                PayloadType.PRESENCE_PARTNER,
                PresencePartnerPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    /**
     * Subscribes to the platform's raw world events.
     *
     * <p>The vocabulary is {@code WorldEventTypes} (api), so the producer needs no knowledge of presence; this
     * module is the aggregator, and the window it builds is what it re-publishes for other consumers.</p>
     */
    public void subscribeWorldEventInput(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.WORLD_EVENT,
                PayloadType.CUSTOM,
                WorldEventPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    /**
     * Sends the facts of one answered query as the response payload and closes the request.
     *
     * <p>The coordinator hands over values, not an envelope: choosing the payload type and completing the
     * request belongs to the protocol side, exactly like every other response this adapter sends.</p>
     */
    public void respondContextFacts(
            TianshuEnvelope parent,
            String requestId,
            List<PresenceContextSnapshotPayload.FactPayload> facts,
            ProtocolContext context
    ) {
        respondTo(parent, PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.success(requestId, facts));
        context.complete(parent.envelopeId());
    }

    public TianshuEnvelope publishWorldEvent(PresenceWorldEventPayload payload) {
        return publishTopic(PresenceWorldEventPayload.TOPIC, PayloadType.CUSTOM, payload);
    }

    /**
     * Publishes the claim-relevant interaction change the watcher detected.
     *
     * <p>The topic is registered by this module (see {@link #registerOwnedTopics}); the payload carries no
     * narrative and no inventory, only what a claim condition can read.</p>
     */
    public TianshuEnvelope publishInteractionContext(PresenceInteractionContextPayload payload) {
        return publishTopic(
                ProtocolTopics.PRESENCE_INTERACTION_CONTEXT,
                PayloadType.PRESENCE_INTERACTION_CONTEXT,
                payload
        );
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        return status == null ? null : publishTopic(
                ProtocolTopics.MODULE_STATUS,
                PayloadType.MODULE_STATUS,
                new ModuleStatusPayload(status)
        );
    }

}

