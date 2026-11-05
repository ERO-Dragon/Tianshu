package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.function.weaving.bridge.LlmPresenceActivityBridge;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LlmPresenceActivityTest {

    @Test
    void runtimeNoticesAreRetractedExactlyOnceWhenTheLaneRecovers() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            installPresence(runtime);
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);

            adapter.publishRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR, 12_000L);
            adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR);
            adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR);

            awaitAction(activities, "llm.runtime.error", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> errorActivities = activities.stream()
                    .filter(activity -> "llm.runtime.error".equals(activity.activityId()))
                    .toList();
            // One notice, one retraction: the second clear has nothing to end, because the lane already
            // reported recovery and the notice is no longer up.
            assertEquals(2, errorActivities.size());
            assertEquals(PresenceActivityAction.STARTED, errorActivities.getFirst().action());
            assertEquals(PresenceActivityAction.ENDED, errorActivities.getLast().action());
        }
    }

    @Test
    void aLaneThatNeverReportedAFailureSendsNoEmptyRetraction() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            installPresence(runtime);
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);

            adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.RECONNECTING);
            adapter.clearRuntimeActivity(LlmPresenceActivityBridge.Kind.ERROR);

            assertEquals(0, activities.size(), "nothing was announced, so there is nothing to retract");
        }
    }

    @Test
    void theCloudLaneStartPublishesOneLoadingNoticeAndRetractsItOnce() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            installPresence(runtime);
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);

            adapter.publishLoadingActivity(true);
            adapter.publishLoadingActivity(false);
            adapter.publishLoadingActivity(false);

            awaitAction(activities, "llm.model.load", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> loading = activities.stream()
                    .filter(activity -> "llm.model.load".equals(activity.activityId()))
                    .toList();

            assertEquals(2, loading.size(), "one start and one retraction; retracting again sends nothing");
            assertEquals(PresenceActivityAction.STARTED, loading.getFirst().action());
            assertEquals(PresenceActivityAction.ENDED, loading.getLast().action());
            assertTrue(loading.stream().allMatch(activity -> activity.activityType() == PresenceActivityType.LOADING));
        }
    }

    /**
     * Presence observation point with a single delivery lane.
     *
     * <p>The test asserts the STARTED → ENDED pair and its order, so arrival order has to be a contract.
     * {@code AdapterDefaults.standard()} runs {@code maxConcurrency} handlers in parallel, which lets the pair
     * land inverted; one lane keeps the dispatch order of {@code BrokerTask} observable.</p>
     */
    /** Registers the presence side of the contract: its topics and its installed-mod guard. */
    private static void installPresence(ProtocolRuntime runtime) {
        TianshuRuntimePorts.register(InstalledMods.class, id -> "tianshu_presence".equals(id));
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
    }
    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        List<PresenceActivityPayload> activities = new java.util.concurrent.CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.llm-test", List.of(), defaults.threadPolicy(), defaults.cancellationScope(),
                        defaults.failurePolicy(), defaults.deliveryPolicy(), defaults.cancellable(),
                        defaults.supportsStreaming(), 1, defaults.queueCapacity()
                ),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.ACTIVITY, PresencePayloadTypes.ACTIVITY, PresenceActivityPayload.class,
                        BrokerType.BOUNDED_QUEUE, EnumSet.of(PacketType.EVENT), Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> activities.add((PresenceActivityPayload) envelope.payload())
        );
        return activities;
    }

    /**
     * Waits for a specific terminal transition instead of for a list length: activity publication is
     * asynchronous, so a length check can be satisfied by intermediate events and then read a
     * half-filled list.
     */
    private static void awaitAction(
            List<PresenceActivityPayload> activities,
            String activityId,
            PresenceActivityAction expected
    ) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            boolean reached = activities.stream()
                    .anyMatch(activity -> activityId.equals(activity.activityId()) && activity.action() == expected);
            if (reached) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("activity " + activityId + " never reached " + expected);
    }
}
