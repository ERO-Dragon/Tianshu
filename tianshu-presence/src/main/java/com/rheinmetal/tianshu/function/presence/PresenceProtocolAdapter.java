package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceCapabilities;
import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;
import java.util.List;

public final class PresenceProtocolAdapter extends AbstractProtocolAdapter {
    /** Presence's protocol identity; the module owns this name instead of the kernel's id table. */
    public static final String MODULE_ID = "module.presence";
    public static final String SOURCE_ID = MODULE_ID;

    private boolean ownedTopicsRegistered;

    public PresenceProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    /**
     * Registers every topic presence owns.
     *
     * <p>It is called while the session is assembled, before any module's {@code register()} runs, which is what lets
     * the activity and partner topics exist by the time their producers publish. The kernel registers no presence
     * topic at all, so removing this module removes them.</p>
     */
    public synchronized void registerOwnedTopics(ModuleProtocolAccess protocol) {
        if (ownedTopicsRegistered) {
            return;
        }
        protocol.registerTopic(new TopicDescriptor(
                PresenceTopics.ACTIVITY,
                PresencePayloadTypes.ACTIVITY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                80
        ));
        protocol.registerTopic(new TopicDescriptor(
                PresenceTopics.WORLD_EVENT,
                PayloadType.CUSTOM,
                DeliveryPolicy.WAIT_IN_QUEUE,
                40
        ));
        protocol.registerTopic(new TopicDescriptor(
                PresenceTopics.INTERACTION_CONTEXT,
                PresencePayloadTypes.INTERACTION_CONTEXT,
                DeliveryPolicy.LATEST_ONLY,
                20
        ));
        protocol.registerTopic(new TopicDescriptor(
                PresenceTopics.PARTNER,
                PresencePayloadTypes.PARTNER,
                DeliveryPolicy.LATEST_ONLY,
                10
        ));
        ownedTopicsRegistered = true;
    }

    public void registerQueryContextCapability(EnvelopeHandler handler) {
        registerCapability(
                PresenceCapabilities.QUERY_CONTEXT,
                PresencePayloadTypes.CONTEXT_QUERY,
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
                PresenceTopics.ACTIVITY,
                PresencePayloadTypes.ACTIVITY,
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
                PresenceTopics.PARTNER,
                PresencePayloadTypes.PARTNER,
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
        respondTo(parent, PresencePayloadTypes.CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.success(requestId, facts));
        context.complete(parent.envelopeId());
    }

    public TianshuEnvelope publishWorldEvent(PresenceWorldEventPayload payload) {
        return publishTopic(PresenceTopics.WORLD_EVENT, PayloadType.CUSTOM, payload);
    }

    /**
     * Publishes the claim-relevant interaction change the watcher detected.
     *
     * <p>The topic is registered by this module (see {@link #registerOwnedTopics}); the payload carries no
     * narrative and no inventory, only what a claim condition can read.</p>
     */
    public TianshuEnvelope publishInteractionContext(PresenceInteractionContextPayload payload) {
        return publishTopic(
                PresenceTopics.INTERACTION_CONTEXT,
                PresencePayloadTypes.INTERACTION_CONTEXT,
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
