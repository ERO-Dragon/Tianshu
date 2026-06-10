package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackController;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackListener;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.text.TtsTextNormalizer;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TtsRuntime implements TtsPlaybackListener {
    private static final String MODULE_ID = "module.tts";

    private final IGameEnvironment env;
    private final ProtocolExecutorManager executorManager;
    private final TtsSynthesisEngine synthesisEngine;
    private final TtsPlaybackController playbackController;
    private final TtsSessionManager sessionManager = new TtsSessionManager();
    private final TtsStreamRegistry streamRegistry = new TtsStreamRegistry();
    private final TtsTextNormalizer normalizer = new TtsTextNormalizer();
    private final Consumer<TtsSession> sessionStatusPublisher;
    private final Consumer<TtsPlaybackState> playbackStatePublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<TtsFailure> lastFailure = new AtomicReference<>();
    private final AtomicReference<TtsPlaybackState> lastPublishedState = new AtomicReference<>();

    public TtsRuntime(IGameEnvironment env, ProtocolExecutorManager executorManager, TtsSynthesisEngine synthesisEngine, IAudioBridge audioBridge, Consumer<TtsSession> sessionStatusPublisher, Consumer<TtsPlaybackState> playbackStatePublisher) {
        this.env = env;
        this.executorManager = executorManager;
        this.synthesisEngine = synthesisEngine;
        this.playbackController = new TtsPlaybackController(audioBridge, env, this);
        this.sessionStatusPublisher = sessionStatusPublisher == null ? ignored -> {} : sessionStatusPublisher;
        this.playbackStatePublisher = playbackStatePublisher == null ? ignored -> {} : playbackStatePublisher;
    }

    public boolean prepare() {
        boolean initialized = synthesisEngine.initialize();
        running.set(true);
        publishPlaybackState();
        return initialized;
    }

    public void start() {
        running.set(true);
        publishPlaybackState();
    }

    public void stop() {
        running.set(false);
        streamRegistry.clear();
        synthesisEngine.interrupt();
        playbackController.stopActive("runtime stopped");
        publishPlaybackState();
    }

    public void destroy() {
        stop();
        sessionManager.clear();
        synthesisEngine.shutdown();
    }

    public boolean isReady() {
        return running.get() && synthesisEngine.isInitialized();
    }

    public ExecutionLane synthesisLane() {
        return synthesisEngine.isAutoregressive() ? ExecutionLane.TTS_AUTOREGRESSIVE : ExecutionLane.TTS_FAST;
    }

    public TtsRuntimeSnapshot snapshot() {
        Optional<TtsSession> active = sessionManager.active();
        TtsSession session = active.orElse(null);
        TtsFailure failure = session != null && session.failure() != null ? session.failure() : lastFailure.get();
        TtsRequest request = session == null ? null : session.request();
        return new TtsRuntimeSnapshot(
                true,
                running.get(),
                isReady(),
                synthesisEngine.isInitialized(),
                synthesisEngine.isAutoregressive(),
                stateOf(session),
                request == null ? "" : request.requestId(),
                request == null ? "" : request.source().name().toLowerCase(),
                request == null ? Priority.NORMAL : request.priority(),
                failure == null ? TtsFailureCode.UNKNOWN : failure.code(),
                failure == null ? "" : failure.message(),
                System.currentTimeMillis()
        );
    }

    public TtsBackendSnapshot backendSnapshot() {
        return synthesisEngine.backendSnapshot();
    }

    public TtsOperationResult submit(TtsRequest request, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        if (!running.get()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not running");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (request == null || request.text() == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS request is invalid");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        String text = normalizer.normalize(request.text());
        if (text.isBlank()) {
            complete(onComplete);
            return TtsOperationResult.accepted(request.requestId());
        }
        TtsRequest normalizedRequest = new TtsRequest(
                request.requestId(),
                request.envelopeId(),
                request.traceId(),
                text,
                request.source(),
                request.playbackPolicy(),
                request.priority(),
                request.voiceProfile(),
                request.expectPlaybackEndEvent()
        );
        boolean interruptActiveAfterSubmit = shouldInterruptActiveAfterSubmit(normalizedRequest);
        if (!acceptBeforeSubmit(normalizedRequest)) {
            complete(onComplete);
            return TtsOperationResult.accepted(normalizedRequest.requestId());
        }
        TtsSession session = sessionManager.create(normalizedRequest);
        transition(session, TtsSessionState.QUEUED);
        ProtocolTaskHandle handle = executorManager.submit(taskSpec(normalizedRequest), () -> runSession(session, onComplete, onFailure));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS synthesis queue is full");
            failSession(session, failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (interruptActiveAfterSubmit) {
            interruptActive("interrupted by " + normalizedRequest.requestId());
        }
        return TtsOperationResult.accepted(normalizedRequest.requestId());
    }

    public TtsOperationResult submitStream(TtsStreamChunk chunk, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        if (!running.get()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not running");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (chunk == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS stream chunk is invalid");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        List<String> segments = streamRegistry.append(chunk);
        if (segments.isEmpty()) {
            complete(onComplete);
            return TtsOperationResult.accepted(chunk.streamId());
        }
        TtsOperationResult result = TtsOperationResult.accepted(chunk.streamId());
        for (int index = 0; index < segments.size(); index++) {
            boolean lastSegment = index == segments.size() - 1;
            TtsRequest request = new TtsRequest(
                    chunk.streamId() + ":" + System.nanoTime() + ":" + index,
                    chunk.envelopeId(),
                    chunk.traceId(),
                    segments.get(index),
                    chunk.source(),
                    chunk.playbackPolicy(),
                    Priority.LOW,
                    chunk.voiceProfile(),
                    chunk.last() && lastSegment
            );
            result = submit(request, lastSegment ? onComplete : null, onFailure);
            if (!result.accepted()) {
                return result;
            }
        }
        return result;
    }

    public TtsControlResult stopAll(String reason) {
        streamRegistry.clear();
        synthesisEngine.interrupt();
        List<TtsSession> cancelled = sessionManager.cancelAll(reason);
        cancelled.forEach(sessionStatusPublisher);
        playbackController.stopActive(reason);
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_ALL, cancelled.size());
    }

    public TtsControlResult interruptActive(String reason) {
        synthesisEngine.interrupt();
        TtsSession cancelled = sessionManager.cancelActive(reason);
        playbackController.stopActive(reason);
        if (cancelled == null) {
            publishPlaybackState();
            return TtsControlResult.accepted(TtsControlAction.STOP_REQUEST, 0);
        }
        sessionStatusPublisher.accept(cancelled);
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_REQUEST, 1);
    }

    public TtsControlResult stopRequest(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS request id is empty");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);
        }
        streamRegistry.cancel(requestId);
        Optional<TtsSession> cancelled = sessionManager.cancel(requestId, reason);
        if (cancelled.isEmpty()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.REQUEST_NOT_FOUND, "TTS request not found: " + requestId.trim());
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);
        }
        TtsSession session = cancelled.get();
        if (playbackController.activeSession() == session) {
            synthesisEngine.interrupt();
            playbackController.stopActive(reason);
        }
        sessionStatusPublisher.accept(session);
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_REQUEST, 1);
    }

    public TtsControlResult stopSource(TtsRequestSource source, String reason) {
        if (source == null || source == TtsRequestSource.UNKNOWN) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS source is invalid");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_SOURCE, failure);
        }
        int count = 0;
        for (TtsSession session : sessionManager.snapshot()) {
            if (session.request().source() == source) {
                TtsControlResult result = stopRequest(session.request().requestId(), reason);
                if (result.accepted()) {
                    count += result.affectedSessions();
                }
            }
        }
        return TtsControlResult.accepted(TtsControlAction.STOP_SOURCE, count);
    }

    public TtsControlResult reloadModel() {
        TtsControlResult stopResult = stopAll("model reload");
        synthesisEngine.shutdown();
        boolean initialized = synthesisEngine.initialize();
        if (!initialized) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine reload failed");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, failure);
        }
        return TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions());
    }

    public TtsOperationResult submitWithModel(String modelName, TtsRequest request, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        return submit(request, () -> useModel(modelName, onComplete, onFailure), failure -> {
            useModel(modelName, null, onFailure);
            fail(onFailure, failure);
        });
    }

    public TtsControlResult useModel(String modelName) {
        TtsControlResult stopResult = stopAll("model switch");
        boolean initialized = synthesisEngine.useModel(modelName);
        if (!initialized) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine model switch failed");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, failure);
        }
        return TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions());
    }

    private void useModel(String modelName, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        TtsControlResult result = useModel(modelName);
        if (!result.accepted()) {
            fail(onFailure, result.failure());
        }
        complete(onComplete);
    }

    @Override
    public void onPlaybackFinished(TtsSession session) {
        if (session == null || session.isTerminal()) {
            playbackController.clearIfActive(session);
            return;
        }
        sessionManager.complete(session);
        sessionStatusPublisher.accept(session);
        playbackController.clearIfActive(session);
        publishPlaybackState();
    }

    private boolean acceptBeforeSubmit(TtsRequest request) {
        return switch (request.playbackPolicy()) {
            case DROP_IF_BUSY -> !playbackController.isBusy();
            case REPLACE_CURRENT, LATEST_ONLY -> {
                stopAll("replaced by " + request.requestId());
                yield true;
            }
            case INTERRUPT_LOWER_PRIORITY, QUEUE -> true;
        };
    }

    private boolean shouldInterruptActiveAfterSubmit(TtsRequest request) {
        if (request.playbackPolicy() != TtsPlaybackPolicy.INTERRUPT_LOWER_PRIORITY) {
            return false;
        }
        TtsSession active = playbackController.activeSession();
        return active != null && request.priority().atLeast(active.request().priority());
    }

    private void runSession(TtsSession session, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        if (!running.get() || session.isTerminal()) {
            complete(onComplete);
            return;
        }
        try {
            sessionManager.activate(session);
            if (session.isTerminal()) {
                complete(onComplete);
                return;
            }
            transition(session, TtsSessionState.SYNTHESIZING);
            if (!synthesisEngine.initialize()) {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine is unavailable");
                failSession(session, failure);
                fail(onFailure, failure);
                return;
            }
            playbackController.begin(session, synthesisEngine.sampleRate());
            sessionStatusPublisher.accept(session);
            publishPlaybackState();
            synthesisEngine.synthesize(session.request(), audio -> playbackController.feed(session, audio));
            if (!session.isTerminal()) {
                playbackController.finish(session);
                sessionStatusPublisher.accept(session);
            }
            complete(onComplete);
        } catch (Throwable t) {
            TtsFailure failure = TtsFailure.fromThrowable(TtsFailureCode.SYNTHESIS_FAILED, t);
            failSession(session, failure);
            env.error("TTS 会话执行失败: " + session.request().requestId(), t);
            playbackController.stopActive("session failed");
            fail(onFailure, failure);
        }
    }

    private ProtocolTaskSpec taskSpec(TtsRequest request) {
        ExecutionLane lane = synthesisLane();
        return ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(lane)
                .envelopeId(request.envelopeId())
                .priority(request.priority())
                .concurrencyKey(MODULE_ID + ":synthesis:" + (lane == ExecutionLane.TTS_AUTOREGRESSIVE ? "autoregressive" : "fast"))
                .maxConcurrency(1)
                .queueCapacity(lane == ExecutionLane.TTS_AUTOREGRESSIVE ? 1 : 8)
                .build();
    }

    private void transition(TtsSession session, TtsSessionState state) {
        session.transition(state);
        sessionStatusPublisher.accept(session);
    }

    private void failSession(TtsSession session, TtsFailure failure) {
        lastFailure.set(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
        session.fail(failure);
        sessionStatusPublisher.accept(session);
        publishPlaybackState();
    }

    private void publishPlaybackState() {
        TtsPlaybackState state = stateOf(sessionManager.active().orElse(null));
        TtsPlaybackState previous = lastPublishedState.getAndSet(state);
        if (previous != state) {
            playbackStatePublisher.accept(state);
        }
    }

    private TtsPlaybackState stateOf(TtsSession session) {
        if (!running.get() || session == null || session.isTerminal()) {
            return TtsPlaybackState.IDLE;
        }
        return session.request().source() == TtsRequestSource.ALERT ? TtsPlaybackState.ALERTING : TtsPlaybackState.SPEAKING;
    }

    private void complete(Runnable onComplete) {
        if (onComplete != null) {
            onComplete.run();
        }
    }

    private void fail(Consumer<TtsFailure> onFailure, TtsFailure failure) {
        if (onFailure != null) {
            onFailure.accept(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
        }
    }
}
