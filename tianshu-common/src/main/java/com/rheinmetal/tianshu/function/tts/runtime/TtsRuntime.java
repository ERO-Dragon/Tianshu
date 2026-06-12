package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackController;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackListener;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final TtsPlaybackBufferTracker playbackBufferTracker = new TtsPlaybackBufferTracker();
    private final TtsAdaptiveSynthesisPolicy synthesisPolicy = new TtsAdaptiveSynthesisPolicy();
    private final AtomicReference<SynthesisOnlyTask> activeSynthesisOnly = new AtomicReference<>();
    private final Map<String, SynthesisOnlyTask> synthesisOnlyTasks = new ConcurrentHashMap<>();
    private final Consumer<TtsSession> sessionStatusPublisher;
    private final Consumer<TtsPlaybackState> playbackStatePublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<TtsFailure> lastFailure = new AtomicReference<>();
    private final AtomicReference<TtsPlaybackState> lastPublishedState = new AtomicReference<>();

    public TtsRuntime(IGameEnvironment env, ProtocolExecutorManager executorManager, TtsSynthesisEngine synthesisEngine, IAudioBridge audioBridge, Consumer<TtsSession> sessionStatusPublisher, Consumer<TtsPlaybackState> playbackStatePublisher) {
        this.env = env;
        this.executorManager = executorManager;
        this.synthesisEngine = synthesisEngine;
        this.playbackController = new TtsPlaybackController(audioBridge, env, this, executorManager);
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
        cancelSynthesisOnly("runtime stopped");
        synthesisOnlyTasks.clear();
        synthesisEngine.interrupt();
        playbackController.stopAll("runtime stopped");
        playbackBufferTracker.clear();
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

    public int sampleRate() {
        return synthesisEngine.sampleRate();
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
        preemptSynthesisOnly("local speak requested: " + request.requestId());
        TtsRequest normalizedRequest = new TtsRequest(
                request.requestId(),
                request.groupId(),
                request.envelopeId(),
                request.traceId(),
                text,
                request.source(),
                request.playbackPolicy(),
                request.priority(),
                request.voiceProfile(),
                request.expectPlaybackEndEvent()
        );
        PlaybackPreparation preparation = preparePlaybackPlacement(normalizedRequest, onFailure);
        if (preparation.result() != null) {
            if (preparation.result().accepted()) {
                complete(onComplete);
            }
            return preparation.result();
        }
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
            if (normalizedRequest.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY && preparation.interruptSynthesisAfterSubmit()) {
                synthesisEngine.interrupt();
                playbackBufferTracker.clear();
                publishPlaybackState();
            } else {
                preemptActiveSentence("interrupted by " + normalizedRequest.requestId());
            }
        }
        return TtsOperationResult.accepted(normalizedRequest.requestId());
    }

    public TtsOperationResult synthesize(TtsRequest request, boolean streaming, long ttlMillis, TtsAudioChunkConsumer onAudio, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        if (!running.get()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not running");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (request == null || request.text() == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS synthesis request is invalid");
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
                request.groupId(),
                request.envelopeId(),
                request.traceId(),
                text,
                request.source(),
                request.playbackPolicy(),
                request.priority(),
                request.voiceProfile(),
                request.expectPlaybackEndEvent()
        );
        SynthesisOnlyTask task = new SynthesisOnlyTask(
                normalizedRequest,
                streaming,
                System.currentTimeMillis() + Math.max(1_000L, ttlMillis),
                onAudio,
                onComplete,
                onFailure
        );
        synthesisOnlyTasks.put(normalizedRequest.requestId(), task);
        ProtocolTaskHandle handle = executorManager.submit(taskSpec(normalizedRequest), () -> runSynthesisOnly(task));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            synthesisOnlyTasks.remove(normalizedRequest.requestId(), task);
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS synthesis queue is full");
            lastFailure.set(failure);
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        return TtsOperationResult.accepted(normalizedRequest.requestId());
    }

    public TtsOperationResult synthesize(TtsRequest request, boolean streaming, TtsAudioChunkConsumer onAudio, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        return synthesize(request, streaming, 30_000L, onAudio, onComplete, onFailure);
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
                    chunk.streamId(),
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
        cancelSynthesisOnly(reason);
        for (SynthesisOnlyTask task : synthesisOnlyTasks.values()) {
            task.cancel(reason == null || reason.isBlank() ? "synthesis task cancelled" : reason);
        }
        synthesisOnlyTasks.clear();
        synthesisEngine.interrupt();
        List<TtsSession> cancelled = sessionManager.cancelAll(reason);
        cancelled.forEach(sessionStatusPublisher);
        playbackController.stopAll(reason);
        playbackBufferTracker.clear();
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_ALL, cancelled.size());
    }

    public TtsControlResult interruptActive(String reason) {
        TtsSession playbackActive = playbackController.activeSession();
        TtsSession target = playbackActive != null ? playbackActive : sessionManager.active().orElse(null);
        if (target == null || target.isTerminal()) {
            publishPlaybackState();
            return TtsControlResult.accepted(TtsControlAction.STOP_CURRENT, 0);
        }
        blockStream(target.request().groupId());
        if (playbackActive == null || sessionManager.active().orElse(null) == target) {
            synthesisEngine.interrupt();
        }
        sessionManager.cancel(target.request().requestId(), reason);
        playbackController.cancel(target, reason);
        playbackBufferTracker.clear();
        TtsSession cancelled = target;
        sessionStatusPublisher.accept(cancelled);
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_CURRENT, 1);
    }

    private TtsControlResult preemptActiveSentence(String reason) {
        TtsSession playbackActive = playbackController.activeSession();
        TtsSession target = playbackActive != null ? playbackActive : sessionManager.active().orElse(null);
        if (target == null || target.isTerminal()) {
            publishPlaybackState();
            return TtsControlResult.accepted(TtsControlAction.STOP_CURRENT, 0);
        }
        if (playbackActive == null || sessionManager.active().orElse(null) == target) {
            synthesisEngine.interrupt();
        }
        sessionManager.cancel(target.request().requestId(), reason);
        playbackController.cancel(target, reason);
        playbackBufferTracker.clear();
        sessionStatusPublisher.accept(target);
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_CURRENT, 1);
    }

    public TtsControlResult stopCurrent(String reason) {
        return interruptActive(reason);
    }

    public TtsControlResult stopRequest(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS request id is empty");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);
        }
        blockStream(requestId);
        int cancelledSynthesisTasks = stopSynthesisRequest(requestId, reason);
        TtsSession synthesisActive = sessionManager.active().orElse(null);
        List<TtsSession> cancelled = sessionManager.cancelRequestGroup(requestId, reason);
        cancelled.forEach(session -> blockStream(session.request().groupId()));
        if (cancelled.isEmpty() && cancelledSynthesisTasks == 0) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.REQUEST_NOT_FOUND, "TTS request not found: " + requestId.trim());
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);
        }
        boolean interruptedActive = false;
        for (TtsSession session : cancelled) {
            if (playbackController.activeSession() == session || synthesisActive == session) {
                interruptedActive = true;
            }
            playbackController.cancel(session, reason);
            sessionStatusPublisher.accept(session);
        }
        if (interruptedActive) {
            synthesisEngine.interrupt();
            playbackBufferTracker.clear();
        }
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_REQUEST, cancelled.size() + cancelledSynthesisTasks);
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
        if (!playbackController.isBusy()) {
            playbackBufferTracker.clear();
        }
        publishPlaybackState();
    }

    private boolean acceptBeforeSubmit(TtsRequest request) {
        return switch (request.playbackPolicy()) {
            case DROP_IF_BUSY -> !playbackController.isBusy() && sessionManager.active().isEmpty();
            case REPLACE_CURRENT, LATEST_ONLY -> {
                stopAll("replaced by " + request.requestId());
                yield true;
            }
            case QUEUE,
                 INSERT_AFTER_SESSION,
                 INSERT_AFTER_SENTENCE,
                 CANCEL_SENTENCE_AND_PLAY,
                 CANCEL_SESSION_AND_PLAY -> true;
        };
    }

    private boolean shouldInterruptActiveAfterSubmit(TtsRequest request) {
        return request.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY
                || request.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY;
    }

    private PlaybackPreparation preparePlaybackPlacement(TtsRequest request, Consumer<TtsFailure> onFailure) {
        boolean interruptSynthesisAfterSubmit = false;
        if (request.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY) {
            String groupId = activeGroupId();
            String reason = "session cancelled by " + request.requestId();
            List<TtsSession> cancelled = sessionManager.cancelGroup(groupId, reason);
            blockStream(groupId);
            for (TtsSession session : cancelled) {
                playbackController.cancel(session, reason);
                sessionStatusPublisher.accept(session);
            }
            if (!cancelled.isEmpty()) {
                interruptSynthesisAfterSubmit = true;
                playbackBufferTracker.clear();
            }
        }
        return new PlaybackPreparation(null, interruptSynthesisAfterSubmit);
    }

    private String activeGroupId() {
        TtsSession playbackActive = playbackController.activeSession();
        if (playbackActive != null && !playbackActive.isTerminal()) {
            return playbackActive.request().groupId();
        }
        TtsSession synthesisActive = sessionManager.active().orElse(null);
        return synthesisActive == null ? "" : synthesisActive.request().groupId();
    }

    private void runSynthesisOnly(SynthesisOnlyTask task) {
        try {
            if (task.expired()) {
                task.fail(TtsFailure.of(TtsFailureCode.EXPIRED, "TTS synthesis task expired: " + task.request().requestId()));
                return;
            }
            activeSynthesisOnly.set(task);
            if (!synthesisEngine.initialize()) {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine is unavailable");
                lastFailure.set(failure);
                task.fail(failure);
                return;
            }
            if (task.cancelled()) {
                return;
            }
            if (task.streaming()) {
                AtomicInteger chunkIndex = new AtomicInteger();
                synthesisEngine.synthesize(task.request(), new SynthesisOnlyAudioSink(TtsSynthesisMode.STREAMING, audio -> {
                    if (!task.cancelled()) {
                        task.acceptAudio(chunkIndex.getAndIncrement(), audio, false);
                    }
                }));
                if (!task.cancelled()) {
                    task.acceptAudio(chunkIndex.get(), new byte[0], true);
                }
            } else {
                List<byte[]> chunks = new ArrayList<>();
                synthesisEngine.synthesize(task.request(), new SynthesisOnlyAudioSink(TtsSynthesisMode.FULL, audio -> {
                    if (!task.cancelled()) {
                        chunks.add(audio);
                    }
                }));
                if (task.cancelled()) {
                    return;
                }
                byte[] merged = merge(chunks);
                task.acceptAudio(0, merged, true);
            }
            task.complete();
        } catch (Throwable t) {
            TtsFailure failure = TtsFailure.fromThrowable(TtsFailureCode.SYNTHESIS_FAILED, t);
            lastFailure.set(failure);
            task.fail(failure);
        } finally {
            activeSynthesisOnly.compareAndSet(task, null);
            synthesisOnlyTasks.remove(task.request().requestId(), task);
        }
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
            int sampleRate = synthesisEngine.sampleRate();
            TtsSynthesisMode mode = synthesisPolicy.decide(synthesisEngine.backendSnapshot(), session.request(), playbackBufferTracker.estimate());
            boolean playbackAlreadyBusy = playbackController.isBusy();
            playbackController.begin(session, sampleRate);
            if (!playbackAlreadyBusy) {
                playbackBufferTracker.begin(sampleRate);
            }
            sessionStatusPublisher.accept(session);
            publishPlaybackState();
            synthesisEngine.synthesize(session.request(), new RuntimeAudioSink(session, mode));
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
            playbackBufferTracker.clear();
            fail(onFailure, failure);
        }
    }

    private ProtocolTaskSpec taskSpec(TtsRequest request) {
        ExecutionLane lane = synthesisLane();
        return ProtocolTaskSpec.builder()
                .moduleId(MODULE_ID)
                .lane(lane)
                .envelopeId(request.envelopeId())
                .priority(schedulingPriority(request))
                .concurrencyKey(MODULE_ID + ":synthesis:" + (lane == ExecutionLane.TTS_AUTOREGRESSIVE ? "autoregressive" : "fast"))
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
    }

    private Priority schedulingPriority(TtsRequest request) {
        Priority base = request == null ? Priority.NORMAL : request.priority();
        TtsPlaybackPolicy policy = request == null ? TtsPlaybackPolicy.QUEUE : request.playbackPolicy();
        return switch (policy) {
            case CANCEL_SENTENCE_AND_PLAY,
                 CANCEL_SESSION_AND_PLAY,
                 REPLACE_CURRENT,
                 LATEST_ONLY -> base.atLeast(Priority.CRITICAL) ? base : Priority.CRITICAL;
            case INSERT_AFTER_SENTENCE,
                 INSERT_AFTER_SESSION -> base.atLeast(Priority.HIGH) ? base : Priority.HIGH;
            case QUEUE,
                 DROP_IF_BUSY -> base;
        };
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
        return alerting(session.request()) ? TtsPlaybackState.ALERTING : TtsPlaybackState.SPEAKING;
    }

    private boolean alerting(TtsRequest request) {
        if (request == null) {
            return false;
        }
        return request.source() == TtsRequestSource.ALERT
                || request.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY
                || request.playbackPolicy() == TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY;
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

    private void preemptSynthesisOnly(String reason) {
        SynthesisOnlyTask task = activeSynthesisOnly.get();
        if (task == null || task.cancelled()) {
            return;
        }
        if (task.cancel(reason == null || reason.isBlank() ? "local speak preempted synthesis task" : reason)) {
            synthesisEngine.interrupt();
        }
    }

    private void cancelSynthesisOnly(String reason) {
        SynthesisOnlyTask task = activeSynthesisOnly.get();
        if (task == null || task.cancelled()) {
            return;
        }
        task.cancel(reason == null || reason.isBlank() ? "synthesis task cancelled" : reason);
    }

    private void blockStream(String streamId) {
        if (streamId != null && !streamId.isBlank()) {
            streamRegistry.cancel(streamId);
        }
    }

    private int stopSynthesisRequest(String requestId, String reason) {
        if (requestId == null || requestId.isBlank()) {
            return 0;
        }
        String normalized = requestId.trim();
        String groupPrefix = normalized.endsWith(":") ? normalized : normalized + ":";
        int count = 0;
        for (SynthesisOnlyTask task : List.copyOf(synthesisOnlyTasks.values())) {
            String taskRequestId = task.request().requestId();
            if (taskRequestId.equals(normalized) || taskRequestId.startsWith(groupPrefix)) {
                if (task.cancel(reason == null || reason.isBlank() ? "synthesis task stopped" : reason)) {
                    count++;
                }
                synthesisOnlyTasks.remove(taskRequestId, task);
                if (activeSynthesisOnly.get() == task) {
                    synthesisEngine.interrupt();
                    activeSynthesisOnly.compareAndSet(task, null);
                }
            }
        }
        return count;
    }

    private static byte[] merge(List<byte[]> chunks) {
        int size = 0;
        for (byte[] chunk : chunks) {
            if (chunk != null) {
                size += chunk.length;
            }
        }
        byte[] merged = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            if (chunk == null || chunk.length == 0) {
                continue;
            }
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }

    private record PlaybackPreparation(TtsOperationResult result, boolean interruptSynthesisAfterSubmit) {
    }

    private static final class SynthesisOnlyTask {
        private final TtsRequest request;
        private final boolean streaming;
        private final long expireAtMillis;
        private final TtsAudioChunkConsumer onAudio;
        private final Runnable onComplete;
        private final Consumer<TtsFailure> onFailure;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private SynthesisOnlyTask(TtsRequest request, boolean streaming, long expireAtMillis, TtsAudioChunkConsumer onAudio, Runnable onComplete, Consumer<TtsFailure> onFailure) {
            this.request = request;
            this.streaming = streaming;
            this.expireAtMillis = expireAtMillis;
            this.onAudio = onAudio;
            this.onComplete = onComplete;
            this.onFailure = onFailure;
        }

        private TtsRequest request() {
            return request;
        }

        private boolean streaming() {
            return streaming;
        }

        private boolean expired() {
            return System.currentTimeMillis() > expireAtMillis;
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private boolean cancel(String reason) {
            cancelled.set(true);
            return fail(TtsFailure.of(TtsFailureCode.CANCELLED, reason));
        }

        private void acceptAudio(int chunkIndex, byte[] audio, boolean last) {
            if (!cancelled() && onAudio != null) {
                onAudio.accept(chunkIndex, audio, last);
            }
        }

        private void complete() {
            if (finished.compareAndSet(false, true) && onComplete != null) {
                onComplete.run();
            }
        }

        private boolean fail(TtsFailure failure) {
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            if (onFailure != null) {
                onFailure.accept(failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure);
            }
            return true;
        }
    }

    private final class SynthesisOnlyAudioSink implements TtsAudioSink {
        private final TtsSynthesisMode mode;
        private final Consumer<byte[]> audioConsumer;

        private SynthesisOnlyAudioSink(TtsSynthesisMode mode, Consumer<byte[]> audioConsumer) {
            this.mode = mode == null ? TtsSynthesisMode.FULL : mode;
            this.audioConsumer = audioConsumer == null ? ignored -> {} : audioConsumer;
        }

        @Override
        public void accept(byte[] audio) {
            if (audio != null && audio.length > 0) {
                audioConsumer.accept(audio);
            }
        }

        @Override
        public TtsSynthesisMode preferredSynthesisMode() {
            return mode;
        }

        @Override
        public void reportSynthesisMetrics(TtsSynthesisMetrics metrics) {
            synthesisPolicy.record(metrics);
        }
    }

    private final class RuntimeAudioSink implements TtsAudioSink {
        private final TtsSession session;
        private final TtsSynthesisMode mode;

        private RuntimeAudioSink(TtsSession session, TtsSynthesisMode mode) {
            this.session = session;
            this.mode = mode == null ? TtsSynthesisMode.FULL : mode;
        }

        @Override
        public void accept(byte[] audio) {
            if (playbackController.feed(session, audio)) {
                playbackBufferTracker.recordPcm16Mono(audio);
            }
        }

        @Override
        public TtsSynthesisMode preferredSynthesisMode() {
            return mode;
        }

        @Override
        public TtsPlaybackBufferEstimate playbackBufferEstimate() {
            return playbackBufferTracker.estimate();
        }

        @Override
        public void reportSynthesisMetrics(TtsSynthesisMetrics metrics) {
            synthesisPolicy.record(metrics);
        }
    }
}
