package com.rheinmetal.tianshu.function.intake.control;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.intake.audio.AudioCaptureService;
import com.rheinmetal.tianshu.function.intake.engine.AsrEngine;
import com.rheinmetal.tianshu.function.intake.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.function.intake.session.AsrSessionManager;
import com.rheinmetal.tianshu.function.intake.state.AsrState;
import com.rheinmetal.tianshu.function.intake.state.AsrStateMachine;
import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listening mark belongs to the speech turn, not to the microphone.
 *
 * <p>It is opened when capture starts and is closed either by the arbiter that resolved the turn or by the
 * speech module itself when the turn produced no text at all. A successful recognition therefore must not
 * end it — that is the invariant this test pins down.</p>
 */
class AsrListeningLifecycleTest {
    /** Longer than the controller's tap window, so these cases exercise speech rather than the stop gesture. */
    private static final long HOLD_MILLIS = 300L;

    @Test
    void aTurnWithTextLeavesTheListeningMarkToTheArbiter() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            Harness harness = new Harness(runtime, "你好");

            harness.speak();

            awaitActivityCount(activities, 3);
            List<PresenceActivityPayload> listening = listening(activities);
            assertEquals(1, listening.size(), "the arbiter is the only one that ends a turn with text");
            assertEquals(PresenceActivityAction.STARTED, listening.get(0).action());
            assertEquals(1, harness.recognitionCalls());
            assertEquals(AsrState.IDLE, harness.stateMachine.state());
        }
    }

    @Test
    void aTurnWithoutTextClosesItsOwnListeningMark() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            Harness harness = new Harness(runtime, "");

            harness.speak();

            awaitActivityCount(activities, 4);
            List<PresenceActivityPayload> listening = listening(activities);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    listening.stream().map(PresenceActivityPayload::action).toList(),
                    "a turn that produced no text has no arbiter left to close it");
            assertEquals(AsrState.IDLE, harness.stateMachine.state());
        }
    }

    @Test
    void aShortTapDiscardsTheTurnAndAsksForAnInterrupt() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            Harness harness = new Harness(runtime, "你好");

            harness.tap();

            awaitActivityCount(activities, 2);
            List<PresenceActivityPayload> listening = listening(activities);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    listening.stream().map(PresenceActivityPayload::action).toList(),
                    "the tap closes the mark it opened");
            assertTrue(harness.tapRequested(), "the module is told to request the interrupt");
            assertEquals(0, harness.recognitionCalls(), "a tap must never become a speech turn");
            assertEquals(AsrState.IDLE, harness.stateMachine.state());
        }
    }

    @Test
    void anEmptyRecordingClosesItsOwnListeningMark() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceActivityPayload> activities = captureActivities(runtime);
            Harness harness = new Harness(runtime, "你好");

            harness.controller.handle(AsrInputIntent.BEGIN);
            sleep(HOLD_MILLIS);
            harness.controller.handle(AsrInputIntent.END);

            awaitActivityCount(activities, 2);
            List<PresenceActivityPayload> listening = listening(activities);
            assertEquals(List.of(PresenceActivityAction.STARTED, PresenceActivityAction.ENDED),
                    listening.stream().map(PresenceActivityPayload::action).toList());
        }
    }

    @Test
    void anInjectedTextTurnTakesTheRecognitionPublishLane() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            Harness harness = new Harness(runtime, "");
            List<com.rheinmetal.tianshu.function.intake.contract.AsrTextPayload> published = new ArrayList<>();
            harness.controller.bindTextRepair((payload, parent) -> published.add(payload));

            assertTrue(harness.controller.injectRecognizedText("阿尔法，帮我找一个钻石"));

            assertEquals(1, published.size());
            assertEquals("阿尔法，帮我找一个钻石", published.get(0).text());
            assertEquals(AsrController.DEBUG_INPUT_MODE, published.get(0).inputMode());
            assertFalse(harness.controller.injectRecognizedText("   "),
                    "blank text is the one input an injected turn cannot express");
            assertEquals(0, harness.recognitionCalls(), "injection must not involve the engine");
        }
    }

    /** Presence delivery is asynchronous; wait for the turn's expected activity count before reading it. */
    private static void awaitActivityCount(List<PresenceActivityPayload> activities, int expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (activities.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static List<PresenceActivityPayload> listening(List<PresenceActivityPayload> activities) {
        return activities.stream()
                .filter(payload -> payload.activityType() == PresenceActivityType.LISTENING)
                .toList();
    }

    private static List<PresenceActivityPayload> captureActivities(ProtocolRuntime runtime) {
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
        List<PresenceActivityPayload> activities = java.util.Collections.synchronizedList(new ArrayList<>());
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.test",
                        List.of(),
                        com.rheinmetal.tianshu.protocol.ThreadPolicy.ASYNC_WORKER,
                        com.rheinmetal.tianshu.protocol.CancellationScope.SELF_ONLY,
                        com.rheinmetal.tianshu.protocol.FailurePolicy.REPORT_ONLY,
                        com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE,
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Harness {
        private final AsrController controller;
        private final AsrStateMachine stateMachine = new AsrStateMachine();
        private final FakeAudioBridge audioBridge = new FakeAudioBridge();
        private final FakeEngine engine;
        private final AtomicLong sessionIds = new AtomicLong();
        private final AtomicBoolean tapRequested = new AtomicBoolean();

        private Harness(ProtocolRuntime runtime, String recognitionResult) {
            FakeEnvironment environment = new FakeEnvironment();
            engine = new FakeEngine(environment, recognitionResult);
            AsrProtocolAdapter adapter = new AsrProtocolAdapter(runtime);
            adapter.registerOwnedTopics(runtime);
            AsrRecognitionService recognition = new AsrRecognitionService(
                    environment,
                    () -> engine,
                    adapter
            );
            AudioCaptureService capture = new AudioCaptureService(audioBridge, environment);
            controller = new AsrController(
                    environment,
                    () -> true,
                    () -> true,
                    sessionIds::incrementAndGet,
                    adapter,
                    stateMachine,
                    new AsrSessionManager(),
                    capture,
                    recognition
            );
            // The real module always binds the repair lane; its IR result is what lets the arbiter close a
            // turn that produced text. A no-op stands in for "the arbiter owns the mark from here on".
            controller.bindTextRepair((payload, parent) -> { });
            controller.bindTapHandler(() -> tapRequested.set(true));
        }

        private void speak() {
            controller.handle(AsrInputIntent.BEGIN);
            audioBridge.push(new byte[]{1, 2, 3, 4});
            sleep(HOLD_MILLIS);
            controller.handle(AsrInputIntent.END);
            awaitIdle();
        }

        private void tap() {
            controller.handle(AsrInputIntent.BEGIN);
            audioBridge.push(new byte[]{1, 2, 3, 4});
            controller.handle(AsrInputIntent.END);
            awaitIdle();
        }

        private boolean tapRequested() {
            return tapRequested.get();
        }

        private int recognitionCalls() {
            return engine.recognitionCalls();
        }

        private void awaitIdle() {
            long deadline = System.currentTimeMillis() + 2_000L;
            while (stateMachine.state() != AsrState.IDLE && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
        }
    }

    private static final class FakeEngine extends AsrEngine {
        private final String result;
        private final AtomicInteger recognitionCalls = new AtomicInteger();

        private FakeEngine(IGameEnvironment environment, String result) {
            super(environment);
            this.result = result;
        }

        @Override
        public String recognizeComplete(byte[] fullAudio) {
            recognitionCalls.incrementAndGet();
            return result;
        }

        @Override
        public boolean supportsCompleteRecognition() {
            return true;
        }

        private int recognitionCalls() {
            return recognitionCalls.get();
        }
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private Consumer<byte[]> streamConsumer;

        private void push(byte[] chunk) {
            Consumer<byte[]> consumer = streamConsumer;
            if (consumer != null) {
                consumer.accept(chunk);
            }
        }

        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() {}
        @Override public byte[] stopRecording() { return new byte[0]; }

        @Override
        public void startStreamRecording(Consumer<byte[]> onAudioChunk) {
            streamConsumer = onAudioChunk;
        }

        @Override
        public void stopStreamRecording() {
            streamConsumer = null;
        }

        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() {}
        @Override public void setOnPlaybackFinished(Runnable callback) {}
        @Override public void stopTtsPlayback() {}
        @Override public void playAudio(byte[] audioData, int sampleRate) {}
        @Override public void stopPlayback() {}
        @Override public boolean isRecording() { return streamConsumer != null; }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return streamConsumer != null; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) {}
        @Override public void switchToNextMic() {}
        @Override public void shutdown() {}
    }

    private static final class FakeEnvironment implements IGameEnvironment {
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
