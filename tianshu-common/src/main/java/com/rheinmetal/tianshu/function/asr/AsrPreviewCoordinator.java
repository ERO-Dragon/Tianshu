package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the cancellable recording window and one-shot resource lifecycle of an ASR preview. */
final class AsrPreviewCoordinator implements AutoCloseable {
    static final Duration DEFAULT_RECORDING_WINDOW = Duration.ofSeconds(5);

    private static final String MODULE_ID = "module.asr";
    private static final String CONCURRENCY_KEY = "module.asr:preview";

    private final IGameEnvironment environment;
    private final IAudioBridge audioBridge;
    private final ProtocolExecutorManager executors;
    private final Duration recordingWindow;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Session> activeSession = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    AsrPreviewCoordinator(
            IGameEnvironment environment,
            IAudioBridge audioBridge,
            ProtocolExecutorManager executors,
            Duration recordingWindow
    ) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.audioBridge = Objects.requireNonNull(audioBridge, "audioBridge");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.recordingWindow = requireNonNegative(recordingWindow);
    }

    boolean start(RecognitionOperation operation, Listener listener) {
        RecognitionOperation previewOperation = Objects.requireNonNull(operation, "operation");
        Listener previewListener = Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            rejectWithoutSession(previewOperation, previewListener, new Failure(FailureCode.CLOSED, null));
            return false;
        }

        Session session = new Session(sequence.incrementAndGet(), previewOperation, previewListener);
        if (!activeSession.compareAndSet(null, session)) {
            rejectWithoutSession(previewOperation, previewListener, new Failure(FailureCode.ALREADY_RUNNING, null));
            return false;
        }

        ProtocolTaskHandle handle = executors.submit(previewTaskSpec("start", session.id), () -> startSession(session));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            Failure failure = new Failure(FailureCode.QUEUE_REJECTED, handle.failureCause().orElse(null));
            completeWithoutCapture(session, failure);
            return false;
        }
        return true;
    }

    boolean stop() {
        Session session = activeSession.get();
        if (session == null || !session.cancelRequested.compareAndSet(false, true)) {
            return false;
        }
        cancelTimer(session);
        requestFinish(session, null);
        return true;
    }

    boolean isRunning() {
        return activeSession.get() != null;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
        }
    }

    private void startSession(Session session) {
        if (!isCurrent(session) || session.cancelRequested.get()) {
            completeWithoutCapture(session, null);
            return;
        }
        try {
            session.operation.prepare();
        } catch (Exception | LinkageError failure) {
            completeWithoutCapture(session, failure(FailureCode.PREPARE_FAILED, failure));
            return;
        }

        if (session.cancelRequested.get()) {
            completeWithoutCapture(session, null);
            return;
        }
        try {
            audioBridge.startRecording();
            session.recording.set(true);
        } catch (RuntimeException | LinkageError failure) {
            completeWithoutCapture(session, failure(FailureCode.CAPTURE_START_FAILED, failure));
            return;
        }
        notifyReady(session.listener);

        if (session.cancelRequested.get()) {
            requestFinish(session, null);
            return;
        }
        ProtocolTaskHandle timer = executors.schedule(
                scheduledTaskSpec(session.id),
                () -> requestFinish(session, null),
                recordingWindow
        );
        session.timer.set(timer);
        if (timer.state() == ProtocolTaskState.REJECTED) {
            requestFinish(session, new Failure(FailureCode.QUEUE_REJECTED, timer.failureCause().orElse(null)));
        } else if (session.cancelRequested.get()) {
            cancelTimer(session);
            requestFinish(session, null);
        }
    }

    private void requestFinish(Session session, Failure failure) {
        if (failure != null) {
            session.pendingFailure.compareAndSet(null, failure);
        }
        if (!session.finishQueued.compareAndSet(false, true)) {
            return;
        }
        ProtocolTaskHandle handle = executors.submit(
                previewTaskSpec("finish", session.id),
                () -> finishSession(session)
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            Failure rejected = new Failure(FailureCode.QUEUE_REJECTED, handle.failureCause().orElse(null));
            session.pendingFailure.compareAndSet(null, rejected);
            environment.error("ASR preview cleanup task was rejected", rejected.cause());
            completeWithoutCapture(session, rejected);
        }
    }

    private void finishSession(Session session) {
        Failure failure = session.pendingFailure.get();
        byte[] audio = null;
        try {
            if (session.recording.compareAndSet(true, false)) {
                try {
                    audio = audioBridge.stopRecording();
                } catch (RuntimeException | LinkageError stopFailure) {
                    failure = failure(FailureCode.CAPTURE_STOP_FAILED, stopFailure);
                }
            }

            if (!session.cancelRequested.get() && failure == null) {
                if (audio == null || audio.length == 0) {
                    failure = new Failure(FailureCode.EMPTY_AUDIO, null);
                } else {
                    String result = null;
                    try {
                        result = session.operation.recognize(audio);
                    } catch (Exception | LinkageError recognitionFailure) {
                        failure = failure(FailureCode.RECOGNITION_FAILED, recognitionFailure);
                    }
                    if (!session.cancelRequested.get() && failure == null) {
                        if (result == null || result.isBlank()) {
                            failure = new Failure(FailureCode.EMPTY_RESULT, null);
                        } else {
                            notifyResult(session.listener, result);
                        }
                    }
                }
            }
        } finally {
            completeSession(session, failure);
        }
    }

    private void completeWithoutCapture(Session session, Failure failure) {
        completeSession(session, failure);
    }

    private void completeSession(Session session, Failure failure) {
        if (!session.terminal.compareAndSet(false, true)) {
            return;
        }
        cancelTimer(session);
        closeOperation(session.operation);
        activeSession.compareAndSet(session, null);
        if (!session.cancelRequested.get() && failure != null) {
            logFailure(failure);
            notifyFailure(session.listener, failure);
        } else if (failure != null && failure.cause() != null) {
            logFailure(failure);
        }
        notifyFinish(session.listener);
    }

    private void rejectWithoutSession(RecognitionOperation operation, Listener listener, Failure failure) {
        closeOperation(operation);
        notifyFailure(listener, failure);
        notifyFinish(listener);
    }

    private void closeOperation(RecognitionOperation operation) {
        try {
            operation.close();
        } catch (Exception | LinkageError failure) {
            environment.error("ASR preview operation close failed", failure);
        }
    }

    private void cancelTimer(Session session) {
        ProtocolTaskHandle timer = session.timer.getAndSet(null);
        if (timer != null && !timer.isDone()) {
            timer.cancel("ASR preview finished");
        }
    }

    private Failure failure(FailureCode code, Throwable cause) {
        return new Failure(code, cause);
    }

    private void logFailure(Failure failure) {
        if (failure.cause() != null) {
            environment.error("ASR preview failed: " + failure.code(), failure.cause());
        } else {
            environment.warn("ASR preview failed: " + failure.code());
        }
    }

    private void notifyReady(Listener listener) {
        try {
            listener.onReady();
        } catch (RuntimeException callbackFailure) {
            environment.error("ASR preview ready callback failed", callbackFailure);
        }
    }

    private void notifyResult(Listener listener, String result) {
        try {
            listener.onResult(result);
        } catch (RuntimeException callbackFailure) {
            environment.error("ASR preview result callback failed", callbackFailure);
        }
    }

    private void notifyFailure(Listener listener, Failure failure) {
        try {
            listener.onFailure(failure);
        } catch (RuntimeException callbackFailure) {
            environment.error("ASR preview failure callback failed", callbackFailure);
        }
    }

    private void notifyFinish(Listener listener) {
        try {
            listener.onFinish();
        } catch (RuntimeException callbackFailure) {
            environment.error("ASR preview finish callback failed", callbackFailure);
        }
    }

    private boolean isCurrent(Session session) {
        return activeSession.get() == session && !session.terminal.get();
    }

    private static ProtocolTaskSpec previewTaskSpec(String action, long sessionId) {
        return ProtocolTaskSpec.builder()
                .taskId("asr-preview:" + action + ":" + sessionId)
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.ASR_STREAM)
                .concurrencyKey(CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
    }

    private static ProtocolTaskSpec scheduledTaskSpec(long sessionId) {
        return ProtocolTaskSpec.builder()
                .taskId("asr-preview:timer:" + sessionId)
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.SCHEDULED)
                .concurrencyKey(CONCURRENCY_KEY + ":timer")
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
    }

    private static Duration requireNonNegative(Duration value) {
        Duration duration = Objects.requireNonNull(value, "recordingWindow");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("recordingWindow must not be negative");
        }
        return duration;
    }

    interface RecognitionOperation extends AutoCloseable {
        void prepare() throws Exception;

        String recognize(byte[] audio) throws Exception;

        @Override
        void close() throws Exception;
    }

    interface Listener {
        void onReady();

        void onResult(String text);

        void onFailure(Failure failure);

        void onFinish();
    }

    enum FailureCode {
        CLOSED,
        ALREADY_RUNNING,
        QUEUE_REJECTED,
        PREPARE_FAILED,
        CAPTURE_START_FAILED,
        CAPTURE_STOP_FAILED,
        EMPTY_AUDIO,
        RECOGNITION_FAILED,
        EMPTY_RESULT
    }

    record Failure(FailureCode code, Throwable cause) {
        Failure {
            Objects.requireNonNull(code, "code");
        }
    }

    private static final class Session {
        private final long id;
        private final RecognitionOperation operation;
        private final Listener listener;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean recording = new AtomicBoolean();
        private final AtomicBoolean finishQueued = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicReference<Failure> pendingFailure = new AtomicReference<>();
        private final AtomicReference<ProtocolTaskHandle> timer = new AtomicReference<>();

        private Session(long id, RecognitionOperation operation, Listener listener) {
            this.id = id;
            this.operation = operation;
            this.listener = listener;
        }
    }
}
