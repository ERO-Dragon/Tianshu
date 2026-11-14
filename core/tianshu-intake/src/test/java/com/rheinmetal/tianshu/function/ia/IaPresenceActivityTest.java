package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceActivityIds;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The arbiter closes the listening mark the speech module opened.
 *
 * <p>Listening is the only turn-scoped activity: it is started by the speech module and ended by whoever resolves
 * the turn. This test pins the contract from the arbiter's side — the id spelling, the type and the action a
 * consumer will see on the wire.</p>
 */
class IaPresenceActivityTest {

    @Test
    void theArbiterEndsTheListeningMarkOfTheResolvedTurn() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            IaProtocolAdapter adapter = new IaProtocolAdapter(runtime);

            adapter.publishListeningEnded(42L);

            awaitSize(activities, 1);
            PresenceActivityPayload payload = activities.get(0);
            assertEquals(PresenceActivityIds.listening(42L), payload.activityId());
            assertEquals(PresenceActivityType.LISTENING, payload.activityType());
            assertEquals(PresenceActivityAction.ENDED, payload.action());
        }
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        // Presence owns its topics now; the kernel no longer registers them.
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
        List<PresenceActivityPayload> activities = new ArrayList<>();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.ia-test",
                        List.of(),
                        ThreadPolicy.ASYNC_WORKER,
                        CancellationScope.SELF_ONLY,
                        FailurePolicy.REPORT_ONLY,
                        DeliveryPolicy.WAIT_IN_QUEUE,
                        false,
                        false,
                        1,
                        32
                ),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.ACTIVITY,
                        PresencePayloadTypes.ACTIVITY,
                        PresenceActivityPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> activities.add((PresenceActivityPayload) envelope.payload())
        );
        return activities;
    }

    private static void awaitSize(List<?> values, int expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (values.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        if (values.size() < expected) {
            fail("expected " + expected + " presence activities, received " + values.size());
        }
    }
}
