package com.rheinmetal.tianshu.protocol.runtime;

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
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleRuntimeAccessTest {
    @Test
    void exposesRegistrationCountsWithoutExposingRegistries() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            ModuleRuntimeAccess access = runtime;
            CapabilityDescriptor capability = capability("TEST.SHARED");
            access.registerModule(module("module.first", List.of(capability)), (envelope, context) -> {
            });
            access.registerModule(module("module.second", List.of(capability)), (envelope, context) -> {
            });
            TopicSubscriptionDescriptor subscription = topic("TEST.TOPIC");
            access.subscribeTopic(module("module.first", List.of()), subscription, (envelope, context) -> {
            });

            assertEquals(2, access.capabilityProviderCount("TEST.SHARED"));
            assertEquals(
                    List.of("module.first", "module.second"),
                    access.capabilityRegistrations("TEST.SHARED").stream()
                            .map(ProtocolCapabilityRegistration::moduleId)
                            .toList()
            );
            assertEquals(1, access.topicSubscriberCount("TEST.TOPIC"));
        }
    }

    @Test
    void delegatesImmediateAndDelayedTasksWithoutExposingExecutorManager() throws Exception {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            ModuleRuntimeAccess access = runtime;
            CountDownLatch immediate = new CountDownLatch(1);
            CountDownLatch delayed = new CountDownLatch(1);

            access.submit(task("immediate", ExecutionLane.CPU), immediate::countDown);
            access.schedule(task("delayed", ExecutionLane.SCHEDULED), delayed::countDown, Duration.ofMillis(10L));

            assertTrue(immediate.await(1, TimeUnit.SECONDS));
            assertTrue(delayed.await(1, TimeUnit.SECONDS));
        }
    }

    private static ProtocolTaskSpec task(String taskId, ExecutionLane lane) {
        return ProtocolTaskSpec.builder()
                .moduleId("module.test")
                .taskId(taskId)
                .lane(lane)
                .build();
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

    private static CapabilityDescriptor capability(String capabilityId) {
        return new CapabilityDescriptor(
                capabilityId,
                PayloadType.CUSTOM,
                TestPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN
        );
    }

    private static TopicSubscriptionDescriptor topic(String topicId) {
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

    private record TestPayload(String value) implements ITianshuPayload {
    }
}
