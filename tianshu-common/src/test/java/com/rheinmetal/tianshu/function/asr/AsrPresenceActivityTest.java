package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.function.asr.recognition.AsrRecognitionResult;
import com.rheinmetal.tianshu.function.asr.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.CancellationScope;
import com.rheinmetal.tianshu.protocol.FailurePolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsrPresenceActivityTest {
    @Test
    void speechActivityPublishesBalancedListeningActivity() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);

            adapter.publishListeningActivity(true, 42L);
            adapter.publishListeningActivity(false, 42L);

            awaitSize(activities, 2);
            assertEquals(PresenceActivityAction.STARTED, activities.get(0).action());
            assertEquals(PresenceActivityAction.ENDED, activities.get(1).action());
            assertEquals(PresenceActivityType.LISTENING, activities.get(0).activityType());
            assertEquals("asr.speech.42", activities.get(0).activityId());
            assertEquals(activities.get(0).activityId(), activities.get(1).activityId());
        }
    }

    @Test
    void modelInitializationPublishesBalancedLoadingActivity() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);

            adapter.publishLoadingActivity(true);
            adapter.publishLoadingActivity(false);

            awaitSize(activities, 2);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals(List.of(PresenceActivityType.LOADING, PresenceActivityType.LOADING),
                    activities.stream().map(PresenceActivityPayload::activityType).toList());
            assertEquals("asr.model.load", activities.get(0).activityId());
        }
    }

    @Test
    void completeRecognitionEndsProcessingActivityAfterSuccess() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AsrRecognitionService service = new AsrRecognitionService(
                    new FakeEnvironment(),
                    () -> new FakeEngine("recognized"),
                    new AsrProtocolAdapter(runtime)
            );

            service.recognizeComplete(new byte[]{1, 2}, 7L, "push_to_talk", ignored -> {}, () -> {});

            awaitSize(activities, 2);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals(List.of(PresenceActivityType.PROCESSING_TASK, PresenceActivityType.PROCESSING_TASK),
                    activities.stream().map(PresenceActivityPayload::activityType).toList());
            assertEquals("asr.recognition.7", activities.get(0).activityId());
        }
    }

    @Test
    void completeRecognitionEndsProcessingActivityAfterFailure() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AsrRecognitionService service = new AsrRecognitionService(
                    new FakeEnvironment(),
                    () -> new FakeEngine(new IllegalStateException("recognition failed")),
                    new AsrProtocolAdapter(runtime)
            );

            service.recognizeComplete(new byte[]{1, 2}, 8L, "push_to_talk", ignored -> {}, () -> {});

            awaitSize(activities, 2);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals("asr.recognition.8", activities.get(0).activityId());
        }
    }

    @Test
    void vadSegmentRecognitionUsesOneBalancedProcessingActivity() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            AsrRecognitionService service = new AsrRecognitionService(
                    new FakeEnvironment(),
                    () -> new FakeEngine("segment result"),
                    new AsrProtocolAdapter(runtime)
            );

            service.startStreaming(9L, ignored -> {}, true);
            service.acceptAudioChunk(new byte[]{1, 2}, 9L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.START_SEGMENT);
            service.acceptAudioChunk(new byte[]{3, 4}, 9L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.END_SEGMENT);

            awaitSize(activities, 2);
            service.stopStreaming();
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    activities.stream().map(PresenceActivityPayload::action).toList());
            assertEquals("asr.recognition.9", activities.get(0).activityId());
        }
    }

    @Test
    void vadStreamingSessionAcceptsAnotherSegmentAfterTheFirstFlush() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            AsrRecognitionService service = new AsrRecognitionService(
                    new FakeEnvironment(),
                    () -> new FakeEngine("segment result"),
                    new AsrProtocolAdapter(runtime)
            );
            List<AsrRecognitionResult> results = java.util.Collections.synchronizedList(new ArrayList<>());

            service.startStreaming(10L, results::add, true);
            service.acceptAudioChunk(new byte[]{1, 2}, 10L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.START_SEGMENT);
            service.acceptAudioChunk(new byte[]{3, 4}, 10L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.END_SEGMENT);
            service.acceptAudioChunk(new byte[]{5, 6}, 10L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.START_SEGMENT);
            service.acceptAudioChunk(new byte[]{7, 8}, 10L, com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter.Decision.END_SEGMENT);

            long deadline = System.currentTimeMillis() + 2_000L;
            while (results.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(2, results.size());
            assertEquals("vad_segment", results.get(1).inputMode());
            service.stopStreaming();
        }
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        List<PresenceActivityPayload> activities = java.util.Collections.synchronizedList(new ArrayList<>());
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.test",
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
                        ProtocolTopics.PRESENCE_ACTIVITY,
                        PayloadType.PRESENCE_ACTIVITY,
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
        assertEquals(expected, values.size());
    }

    private static final class FakeEngine extends AsrEngine {
        private final String result;
        private final RuntimeException failure;

        private FakeEngine(String result) {
            super(new FakeEnvironment());
            this.result = result;
            this.failure = null;
        }

        private FakeEngine(RuntimeException failure) {
            super(new FakeEnvironment());
            this.result = "";
            this.failure = failure;
        }

        @Override
        public String recognizeComplete(byte[] fullAudio) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public boolean supportsCompleteRecognition() {
            return true;
        }
    }

    private static final class FakeEnvironment implements IGameEnvironment {
        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) {}
        @Override public DiagnosticSink diagnostics() { return DiagnosticSink.NOOP; }
    }
}
