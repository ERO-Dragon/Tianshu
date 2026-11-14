package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolRegistryUnregisterTest {
    private static final EnvelopeHandler NOOP_HANDLER = (envelope, context) -> {
    };

    @Test
    void topicUnregisterKeepsOtherModuleSubscriptionsOnSharedTopic() {
        TopicSubscriptionRegistry registry = new TopicSubscriptionRegistry();
        TopicSubscriptionDescriptor subscription = topicSubscription("TEST.SHARED_TOPIC");
        registry.subscribe(module("module.first", List.of()), subscription, NOOP_HANDLER);
        registry.subscribe(module("module.second", List.of()), subscription, NOOP_HANDLER);

        assertDoesNotThrow(() -> registry.unregisterModule("module.first"));

        assertEquals(List.of("module.second"), moduleIds(registry.findTopic("TEST.SHARED_TOPIC")));
    }

    @Test
    void capabilityUnregisterKeepsOtherProvidersOfSharedCapability() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilityDescriptor capability = capability("TEST.SHARED_CAPABILITY");
        registry.register(module("module.first", List.of(capability)), NOOP_HANDLER);
        registry.register(module("module.second", List.of(capability)), NOOP_HANDLER);

        assertDoesNotThrow(() -> registry.unregisterModule("module.first"));

        assertEquals(List.of("module.second"), moduleIds(registry.findCapability("TEST.SHARED_CAPABILITY")));
    }

    @Test
    void responseUnregisterKeepsOtherHandlersForSameRequest() {
        ResponseHandlerRegistry registry = new ResponseHandlerRegistry();
        CapabilityDescriptor response = capability("TEST.RESPONSE");
        registry.register("request-1", module("module.first", List.of()), response, NOOP_HANDLER);
        registry.register("request-1", module("module.second", List.of()), response, NOOP_HANDLER);

        assertDoesNotThrow(() -> registry.unregisterModule("module.first"));

        assertEquals(List.of("module.second"), moduleIds(registry.findResponse("request-1", PayloadType.CUSTOM)));
    }

    private static List<String> moduleIds(List<HandlerRegistration> registrations) {
        return registrations.stream()
                .map(registration -> registration.moduleDescriptor().moduleId())
                .toList();
    }

    private static ModuleDescriptor module(String moduleId, List<CapabilityDescriptor> capabilities) {
        return new ModuleDescriptor(
                moduleId,
                capabilities,
                ThreadPolicy.ANY,
                CancellationScope.SELF_ONLY,
                FailurePolicy.REPORT_ONLY,
                DeliveryPolicy.WAIT_IN_QUEUE,
                false,
                false,
                1,
                16
        );
    }

    private static TopicSubscriptionDescriptor topicSubscription(String topicId) {
        return new TopicSubscriptionDescriptor(
                topicId,
                PayloadType.CUSTOM,
                TestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
    }

    private static CapabilityDescriptor capability(String capabilityId) {
        return new CapabilityDescriptor(
                capabilityId,
                PayloadType.CUSTOM,
                TestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND, PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
    }

    private record TestPayload(String value) implements ITianshuPayload {
    }
}
