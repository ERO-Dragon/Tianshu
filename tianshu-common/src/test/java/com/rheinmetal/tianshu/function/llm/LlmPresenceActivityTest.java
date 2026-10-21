package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
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
import static org.junit.jupiter.api.Assertions.fail;

class LlmPresenceActivityTest {
    @TempDir
    Path tempDir;

    @Test
    void taskInferencePublishesProcessingButChatDoesNotPublishThinking() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            LlmModule module = new LlmModule(
                    new TestLlmSupport.FakeGameEnvironment(),
                    new TestLlmSupport.FakeConfig(tempDir),
                    runtime
            );

            module.publishInferenceStatus(status("task-1", "TASK", LlmStatusPayload.STARTED));
            module.publishInferenceStatus(status("chat-1", "CHAT", LlmStatusPayload.STARTED));
            module.publishInferenceStatus(status("chat-1", "CHAT", LlmStatusPayload.COMPLETED));
            module.publishInferenceStatus(status("task-1", "TASK", LlmStatusPayload.COMPLETED));

            awaitAction(activities, "llm.task.task-1", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> taskActivities = activities.stream()
                    .filter(activity -> "llm.task.task-1".equals(activity.activityId()))
                    .toList();
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    taskActivities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals(List.of(PresenceActivityType.PROCESSING_TASK, PresenceActivityType.PROCESSING_TASK),
                    taskActivities.stream().map(PresenceActivityPayload::activityType).toList());
            assertEquals("llm.task.task-1", activities.get(0).activityId());
        }
    }

    private static LlmStatusPayload status(String taskId, String taskType, String eventType) {
        return new LlmStatusPayload(taskId, taskType, taskType, eventType, 0, "", 0, 0, "", System.currentTimeMillis());
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        List<PresenceActivityPayload> activities = new java.util.concurrent.CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.llm-test", List.of(), defaults.threadPolicy(), defaults.cancellationScope(),
                        defaults.failurePolicy(), defaults.deliveryPolicy(), defaults.cancellable(),
                        defaults.supportsStreaming(), defaults.maxConcurrency(), defaults.queueCapacity()
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
