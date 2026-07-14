package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrPreviewCoordinatorTest {
    private final ProtocolExecutorManager executors = new ProtocolExecutorManager(Runnable::run);
    private final FakeEnvironment environment = new FakeEnvironment();
    private final FakeAudioBridge audioBridge = new FakeAudioBridge();
    private AsrPreviewCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        executors.close();
    }

    @Test
    void recordingWindowDoesNotOccupyAsrStreamLane() throws Exception {
        coordinator = coordinator(Duration.ofDays(1));
        RecordingOperation operation = new RecordingOperation("recognized");
        RecordingListener listener = new RecordingListener();
        assertTrue(coordinator.start(operation, listener));
        assertTrue(listener.ready.await(2, TimeUnit.SECONDS));

        CountDownLatch laneAvailable = new CountDownLatch(1);
        executors.submit(previewTaskSpec(), laneAvailable::countDown);

        assertTrue(laneAvailable.await(2, TimeUnit.SECONDS), "ASR_STREAM remained occupied during recording");
        assertTrue(coordinator.stop());
        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
    }

    @Test
    void stopRunsAudioCleanupOnManagedLaneAndFinishesOnce() throws Exception {
        coordinator = coordinator(Duration.ofDays(1));
        RecordingOperation operation = new RecordingOperation("ignored");
        RecordingListener listener = new RecordingListener();
        String callerThread = Thread.currentThread().getName();
        assertTrue(coordinator.start(operation, listener));
        assertTrue(listener.ready.await(2, TimeUnit.SECONDS));

        assertTrue(coordinator.stop());
        assertFalse(coordinator.stop());

        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
        assertNotEquals(callerThread, audioBridge.stopThread.get());
        assertEquals(1, audioBridge.stopCalls.get());
        assertEquals(1, operation.closeCalls.get());
        assertEquals(1, listener.finishCalls.get());
        assertFalse(coordinator.isRunning());
    }

    @Test
    void completedWindowStopsAudioAndRecognizesOnce() throws Exception {
        coordinator = coordinator(Duration.ZERO);
        RecordingOperation operation = new RecordingOperation("recognized text");
        RecordingListener listener = new RecordingListener();

        assertTrue(coordinator.start(operation, listener));

        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("recognized text"), listener.results);
        assertTrue(listener.failures.isEmpty());
        assertEquals(1, operation.prepareCalls.get());
        assertEquals(1, operation.recognizeCalls.get());
        assertEquals(1, operation.closeCalls.get());
        assertEquals(1, audioBridge.stopCalls.get());
    }

    @Test
    void emptyAudioReturnsStructuredFailureAndClosesOperation() throws Exception {
        audioBridge.audio = new byte[0];
        coordinator = coordinator(Duration.ZERO);
        RecordingOperation operation = new RecordingOperation("unused");
        RecordingListener listener = new RecordingListener();

        assertTrue(coordinator.start(operation, listener));

        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
        assertEquals(1, listener.failures.size());
        assertEquals(AsrPreviewCoordinator.FailureCode.EMPTY_AUDIO, listener.failures.getFirst().code());
        assertEquals(0, operation.recognizeCalls.get());
        assertEquals(1, operation.closeCalls.get());
    }

    @Test
    void audioStopFailurePreservesCauseAndCompletesSession() throws Exception {
        IllegalStateException failure = new IllegalStateException("capture stop failed");
        audioBridge.stopFailure = failure;
        coordinator = coordinator(Duration.ZERO);
        RecordingOperation operation = new RecordingOperation("unused");
        RecordingListener listener = new RecordingListener();

        assertTrue(coordinator.start(operation, listener));

        assertTrue(listener.finished.await(2, TimeUnit.SECONDS));
        assertEquals(1, listener.failures.size());
        assertEquals(AsrPreviewCoordinator.FailureCode.CAPTURE_STOP_FAILED, listener.failures.getFirst().code());
        assertSame(failure, listener.failures.getFirst().cause());
        assertSame(failure, environment.lastError.get());
        assertEquals(1, operation.closeCalls.get());
        assertFalse(coordinator.isRunning());
    }

    private AsrPreviewCoordinator coordinator(Duration recordingWindow) {
        return new AsrPreviewCoordinator(environment, audioBridge, executors, recordingWindow);
    }

    private static ProtocolTaskSpec previewTaskSpec() {
        return ProtocolTaskSpec.builder()
                .moduleId("module.asr")
                .lane(ExecutionLane.ASR_STREAM)
                .concurrencyKey("module.asr:preview")
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
    }

    private static final class RecordingOperation implements AsrPreviewCoordinator.RecognitionOperation {
        private final String result;
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger recognizeCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private RecordingOperation(String result) {
            this.result = result;
        }

        @Override
        public void prepare() {
            prepareCalls.incrementAndGet();
        }

        @Override
        public String recognize(byte[] audio) {
            recognizeCalls.incrementAndGet();
            return result;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class RecordingListener implements AsrPreviewCoordinator.Listener {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<String> results = new CopyOnWriteArrayList<>();
        private final List<AsrPreviewCoordinator.Failure> failures = new CopyOnWriteArrayList<>();
        private final AtomicInteger finishCalls = new AtomicInteger();

        @Override
        public void onReady() {
            ready.countDown();
        }

        @Override
        public void onResult(String text) {
            results.add(text);
        }

        @Override
        public void onFailure(AsrPreviewCoordinator.Failure failure) {
            failures.add(failure);
        }

        @Override
        public void onFinish() {
            finishCalls.incrementAndGet();
            finished.countDown();
        }
    }

    private static final class FakeEnvironment implements IGameEnvironment {
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();

        @Override public void displayMessageToPlayer(String message) {}
        @Override public void executeOnMainThread(Runnable task) { task.run(); }
        @Override public Path getGameDirectory() { return Path.of("."); }
        @Override public boolean isClientSide() { return true; }
        @Override public void openFolder(Path dir) {}
        @Override public void info(String msg) {}
        @Override public void warn(String msg) {}
        @Override public void error(String msg, Throwable t) { lastError.set(t); }
    }

    private static final class FakeAudioBridge implements IAudioBridge {
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicReference<String> stopThread = new AtomicReference<>();
        private final AtomicBoolean recording = new AtomicBoolean();
        private byte[] audio = new byte[]{1, 2, 3};
        private RuntimeException stopFailure;

        @Override public void ensureHardwareRunning() {}
        @Override public void releaseCaptureHardware() {}
        @Override public void startRecording() { recording.set(true); }
        @Override public byte[] stopRecording() {
            stopCalls.incrementAndGet();
            stopThread.set(Thread.currentThread().getName());
            recording.set(false);
            if (stopFailure != null) throw stopFailure;
            return audio;
        }
        @Override public void startStreamRecording(Consumer<byte[]> onAudioChunk) {}
        @Override public void stopStreamRecording() {}
        @Override public void startTtsPlayback(int sampleRate) {}
        @Override public void feedTtsAudio(byte[] audio) {}
        @Override public void finishTtsPlayback() {}
        @Override public void setOnPlaybackFinished(Runnable callback) {}
        @Override public void stopTtsPlayback() {}
        @Override public void playAudio(byte[] audioData, int sampleRate) {}
        @Override public void stopPlayback() {}
        @Override public boolean isRecording() { return recording.get(); }
        @Override public boolean isPlaying() { return false; }
        @Override public boolean isStreaming() { return false; }
        @Override public List<String> getAvailableMicNames() { return List.of(); }
        @Override public String getCurrentMicName() { return ""; }
        @Override public void selectMic(String micName) {}
        @Override public void switchToNextMic() {}
        @Override public void shutdown() {}
    }
}
