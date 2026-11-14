package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the wire contract presence had while it still lived in the kernel.
 *
 * <p>Every value below is read from commit {@code 74788a8}, the last commit before the extraction: the two topics
 * the kernel registered in {@code ProtocolBootstrap}, the two topics presence registered itself, the payload names
 * from {@code PayloadType}, and the query capability from {@code ProtocolCapabilities}. Those strings are the
 * boundary other modules speak, so a rename or a changed storm limit is a compatibility change and must be a
 * deliberate edit of this test rather than a silent drift.</p>
 *
 * <p>The test also proves the ownership move is complete: the kernel contributes no {@code PRESENCE.*} topic, and
 * registering presence's own topics produces exactly the four descriptors the pre-extraction build served.</p>
 */
final class PresenceWireContractTest {

    private static final String MODULE_ID = "module.presence";

    @Test
    void payloadNamesAndClassesAreTheOnesThePreExtractionBuildShipped() {
        assertPayload(PresencePayloadTypes.ACTIVITY, "PRESENCE_ACTIVITY", PresenceActivityPayload.class);
        assertPayload(PresencePayloadTypes.CONTEXT_QUERY, "PRESENCE_CONTEXT_QUERY", PresenceContextQueryPayload.class);
        assertPayload(PresencePayloadTypes.CONTEXT_SNAPSHOT, "PRESENCE_CONTEXT_SNAPSHOT",
                PresenceContextSnapshotPayload.class);
        assertPayload(PresencePayloadTypes.INTERACTION_CONTEXT, "PRESENCE_INTERACTION_CONTEXT",
                PresenceInteractionContextPayload.class);
        assertPayload(PresencePayloadTypes.PARTNER, "PRESENCE_PARTNER", PresencePartnerPayload.class);
    }

    @Test
    void topicsCarryTheSameNamesPoliciesAndLimits() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(runtime);
            adapter.registerOwnedTopics(runtime);

            assertTopic(runtime, PresenceTopics.ACTIVITY, "PRESENCE_ACTIVITY", DeliveryPolicy.WAIT_IN_QUEUE, 80);
            assertTopic(runtime, PresenceTopics.WORLD_EVENT, "CUSTOM", DeliveryPolicy.WAIT_IN_QUEUE, 40);
            assertTopic(runtime, PresenceTopics.INTERACTION_CONTEXT, "PRESENCE_INTERACTION_CONTEXT",
                    DeliveryPolicy.LATEST_ONLY, 20);
            assertTopic(runtime, PresenceTopics.PARTNER, "PRESENCE_PARTNER", DeliveryPolicy.LATEST_ONLY, 10);

            long presenceTopics = runtime.topics().snapshot().stream()
                    .filter(descriptor -> descriptor.topicId().startsWith("PRESENCE."))
                    .count();
            assertEquals(4, presenceTopics, "presence owns exactly its four topics");
        }
    }

    @Test
    void queryContextCapabilityIsUnchanged() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(runtime);
            adapter.registerQueryContextCapability((envelope, context) -> { });
            adapter.registerOwnedTopics(runtime);

            ModuleDescriptor module = runtime.modules().find(MODULE_ID).orElseThrow();
            assertEquals(1, module.capabilities().size());
            CapabilityDescriptor capability = module.capabilities().get(0);
            assertEquals("PRESENCE.QUERY_CONTEXT", capability.capabilityId());
            assertEquals("PRESENCE_CONTEXT_QUERY", capability.supportedPayloadType().name());
            assertEquals(PresenceContextQueryPayload.class, capability.payloadClass());
            assertEquals(BrokerType.STATELESS_FAST_PATH, capability.requiredBrokerType());
            assertEquals(Set.of(PacketType.REQUEST), capability.acceptedPacketTypes());
            assertEquals(Priority.LOW, capability.minAcceptedPriority());
            assertEquals(CompletionPolicy.MANUAL_COMPLETE, capability.completionPolicy());
        }
    }

    @Test
    void moduleIdIsTheOneOtherModulesAddress() {
        assertEquals(MODULE_ID, PresenceProtocolAdapter.MODULE_ID);
        assertEquals(MODULE_ID, PresenceProtocolAdapter.SOURCE_ID);
    }

    private static void assertPayload(PayloadType type, String expectedName, Class<?> expectedClass) {
        assertEquals(expectedName, type.name());
        assertEquals(expectedClass, PayloadType.payloadClassOf(expectedName),
                "the payload name must stay bound to its class");
    }

    private static void assertTopic(
            ProtocolRuntime runtime,
            String topicId,
            String expectedPayloadName,
            DeliveryPolicy expectedPolicy,
            int expectedLimit
    ) {
        TopicDescriptor descriptor = runtime.topics().find(topicId)
                .orElseThrow(() -> new AssertionError("topic missing: " + topicId));
        assertEquals(expectedPayloadName, descriptor.payloadType().name(), "payload of " + topicId);
        assertEquals(expectedPolicy, descriptor.deliveryPolicy(), "delivery policy of " + topicId);
        assertEquals(expectedLimit, descriptor.stormLimitPerSecond(), "storm limit of " + topicId);
    }
}
