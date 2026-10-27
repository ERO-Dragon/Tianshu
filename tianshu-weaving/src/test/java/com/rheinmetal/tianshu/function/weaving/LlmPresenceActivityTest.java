package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.weaving.contract.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LlmPresenceActivityTest {
    @TempDir
    Path tempDir;

    @Test
    void taskInferencePublishesProcessingButChatDoesNotPublishThinking() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmModule module = new LlmModule(
                    new FakeGameEnvironment(),
                    new TestLlmSupport.FakeConfig(tempDir),
                    runtime
            );

            module.publishInferenceStatus(status("task-1", "COMPLEX", LlmStatusPayload.STARTED));
            module.publishInferenceStatus(status("chat-1", "SIMPLE", LlmStatusPayload.STARTED));
            module.publishInferenceStatus(status("chat-1", "SIMPLE", LlmStatusPayload.COMPLETED));
            module.publishInferenceStatus(status("task-1", "COMPLEX", LlmStatusPayload.COMPLETED));

            awaitAction(activities, "llm.task.task-1", PresenceActivityAction.STARTED);
            awaitAction(activities, "llm.task.task-1", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> taskActivities = activities.stream()
                    .filter(activity -> "llm.task.task-1".equals(activity.activityId()))
                    .toList();
            // Exactly one pair: the task lane opens and closes once, and the chat lane publishes nothing at
            // all. A duplicate report or a chat-lane event fails here; the order of the pair is a contract
            // because the observation point runs on a single delivery lane (see captureActivities).
            assertEquals(2, activities.size(), "the TASK lane opens and closes once; the CHAT lane publishes nothing");
            assertEquals(PresenceActivityAction.STARTED, taskActivities.getFirst().action());
            assertEquals(PresenceActivityAction.ENDED, taskActivities.getLast().action());
            assertTrue(taskActivities.stream()
                    .allMatch(activity -> activity.activityType() == PresenceActivityType.PROCESSING_TASK));
            assertEquals("llm.task.task-1", activities.get(0).activityId());
        }
    }

    @Test
    void runtimeNoticesAreRetractedExactlyOnceWhenTheLaneRecovers() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);

            adapter.publishRuntimeActivity(PresenceActivityType.ERROR, 12_000L);
            adapter.clearRuntimeActivity(PresenceActivityType.ERROR);
            adapter.clearRuntimeActivity(PresenceActivityType.ERROR);

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
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmProtocolAdapter adapter = new LlmProtocolAdapter(runtime, null);

            adapter.clearRuntimeActivity(PresenceActivityType.RECONNECTING);
            adapter.clearRuntimeActivity(PresenceActivityType.DEGRADED);

            assertEquals(0, activities.size(), "nothing was announced, so there is nothing to retract");
        }
    }

    private static LlmStatusPayload status(String taskId, String taskType, String eventType) {
        return new LlmStatusPayload(taskId, taskType, taskType, eventType, 0, "", 0, 0, "", System.currentTimeMillis());
    }

    /**
     * Presence observation point with a single delivery lane.
     *
     * <p>The test asserts the STARTED → ENDED pair and its order, so arrival order has to be a contract.
     * {@code AdapterDefaults.standard()} runs {@code maxConcurrency} handlers in parallel, which lets the pair
     * land inverted; one lane keeps the dispatch order of {@code BrokerTask} observable.</p>
     */
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
                        ProtocolTopics.PRESENCE_ACTIVITY, PayloadType.PRESENCE_ACTIVITY, PresenceActivityPayload.class,
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
