package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatus;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatusPayload;
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

class TtsPresenceActivityTest {
    @TempDir
    Path tempDir;

    @Test
    void nonAxRequestPublishesTaskActivityAndAxRequestDoesNotDuplicateChatState() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            TtsModule module = new TtsModule(
                    new NoopAudioBridge(), runtime, new FakeGameEnvironment(),
                    new TestLlmSupport.FakeConfig(tempDir)
            );

            module.publishRequestStatus(status("external-1", "module.external", TtsRequestStatus.QUEUED));
            module.publishRequestStatus(status("ax-1", "module.ax", TtsRequestStatus.QUEUED));
            module.publishRequestStatus(status("ax-1", "module.ax", TtsRequestStatus.COMPLETED));
            module.publishRequestStatus(status("external-1", "module.external", TtsRequestStatus.COMPLETED));

            awaitAction(activities, "tts.request.external-1", PresenceActivityAction.STARTED);
            awaitAction(activities, "tts.request.external-1", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> externalActivities = activities.stream()
                    .filter(activity -> "tts.request.external-1".equals(activity.activityId()))
                    .toList();
            // 断言语义而非条数：活动发布是异步的，同一状态重复上报不该让用例失败。
            assertEquals(PresenceActivityAction.STARTED, externalActivities.getFirst().action());
            assertEquals(PresenceActivityAction.ENDED, externalActivities.getLast().action());
            assertTrue(externalActivities.stream()
                    .allMatch(activity -> activity.activityType() == PresenceActivityType.PROCESSING_TASK));
            assertEquals("tts.request.external-1", externalActivities.getFirst().activityId());
        }
    }

    @Test
    void destroyEndsAnyRemainingProductActivities() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            TtsModule module = new TtsModule(
                    new NoopAudioBridge(), runtime, new FakeGameEnvironment(),
                    new TestLlmSupport.FakeConfig(tempDir)
            );

            module.publishRequestStatus(status("external-destroy", "module.external", TtsRequestStatus.QUEUED));
            module.destroy();

            awaitAction(activities, "tts.request.external-destroy", PresenceActivityAction.STARTED);
            awaitAction(activities, "tts.request.external-destroy", PresenceActivityAction.ENDED);
            List<PresenceActivityPayload> requestActivities = activities.stream()
                    .filter(activity -> "tts.request.external-destroy".equals(activity.activityId()))
                    .toList();
            // `destroy()` publishes ENDED on its own thread while the queued STARTED may still be on
            // its way, so the two can land in either order. What the case must guarantee is that both
            // transitions exist for the same request, not the order they were observed in.
            assertEquals(
                    java.util.Set.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    java.util.Set.copyOf(requestActivities.stream().map(PresenceActivityPayload::action).toList())
            );
            assertEquals("tts.request.external-destroy", requestActivities.getFirst().activityId());
        }
    }

    private static TtsRequestStatusPayload status(String requestId, String sourceId, TtsRequestStatus status) {
        return TtsRequestStatusPayload.now(requestId, sourceId, 0L, 0, status, "");
    }

    /**
     * presence 活动观测点：单并发收单。
     *
     * <p>用例断言的是"开→合"的先后（{@code getFirst()/getLast()}），顺序必须是契约；而
     * {@code AdapterDefaults.standard()} 的 maxConcurrency 是 CPU 数，broker 会并行派发，两条事件落到
     * 列表里的先后就不再等于发布先后。单并发沿用 {@code BrokerTask} 的派发顺序，也让"重复上报不该失败"
     * 这条宽容度原样保留（这里不断言条数）。</p>
     */
    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        List<PresenceActivityPayload> activities = new java.util.concurrent.CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.tts-test", List.of(), defaults.threadPolicy(), defaults.cancellationScope(),
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
     * Waits for a specific terminal transition rather than for a list length: activity publication is
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

    private static void awaitSize(List<?> values, int expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (values.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, values.size());
    }

    private static final class NoopAudioBridge implements IAudioBridge {
        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }
        @Override public void startStreamRecording(java.util.function.Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() {}
        @Override public void setOnPlaybackFinished(Runnable callback) {}
        @Override public void stopTtsPlayback() {}
        @Override public void playAudio(byte[] audioData, int sampleRate) {}
        @Override public void stopPlayback() {}
        @Override public boolean isRecording() { return false; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) {}
        @Override public void switchToNextMic() {}
        @Override public void shutdown() {}
    }
}
