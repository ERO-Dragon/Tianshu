package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackController;
import com.rheinmetal.tianshu.function.tts.playback.TtsPlaybackListener;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsAudioSink;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsPlaybackBufferEstimate;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMetrics;
import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisMode;
import com.rheinmetal.tianshu.function.tts.text.TtsTextNormalizer;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatus;
import com.rheinmetal.tianshu.protocol.payload.TtsRequestStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.function.Consumer;

public final class TtsRuntime implements TtsPlaybackListener {
    private final IGameEnvironment env;
    private final ModuleExecutionAccess executorManager;
    private final TtsSynthesisEngine synthesisEngine;
    private final TtsSynthesisScheduler synthesisScheduler;
    private final TtsSynthesisTaskCoordinator synthesisTaskCoordinator;
    private final TtsModelLifecycleCoordinator modelLifecycleCoordinator;
    private final TtsPlaybackController playbackController;
    private final TtsSessionManager sessionManager = new TtsSessionManager();
    private final TtsSpeechInputAssembler speechInputAssembler = new TtsSpeechInputAssembler();
    private final TtsSpeechSessionCoordinator speechSessionCoordinator = new TtsSpeechSessionCoordinator();
    private final Map<TtsSpeechSessionKey, SpeechContext> speechContexts = new ConcurrentHashMap<>();
    private final Map<TtsSession, TtsSpeechSessionCoordinator.SentenceWork> sentenceWorks = new ConcurrentHashMap<>();
    private final Map<TtsSpeechSessionCoordinator.SentenceWork, TtsSession> workSessions = new ConcurrentHashMap<>();
    private final Set<TtsSpeechSessionKey> droppedStreams = ConcurrentHashMap.newKeySet();
    private final TtsTextNormalizer normalizer = new TtsTextNormalizer();
    private final TtsPlaybackBufferTracker playbackBufferTracker = new TtsPlaybackBufferTracker();
    private final TtsAdaptiveSynthesisPolicy synthesisPolicy = new TtsAdaptiveSynthesisPolicy();
    private final Consumer<TtsSession> sessionStatusPublisher;
    private final Consumer<TtsPlaybackState> playbackStatePublisher;
    private final Consumer<TtsRequestStatusPayload> requestStatusPublisher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<TtsFailure> lastFailure = new AtomicReference<>();
    private final AtomicReference<TtsPlaybackState> lastPublishedState = new AtomicReference<>();

    public TtsRuntime(IGameEnvironment env, ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine, IAudioBridge audioBridge, Consumer<TtsSession> sessionStatusPublisher, Consumer<TtsPlaybackState> playbackStatePublisher) {
        this(env, executorManager, synthesisEngine, audioBridge, sessionStatusPublisher, playbackStatePublisher, ignored -> { });
    }

    public TtsRuntime(
            IGameEnvironment env,
            ModuleExecutionAccess executorManager,
            TtsSynthesisEngine synthesisEngine,
            IAudioBridge audioBridge,
            Consumer<TtsSession> sessionStatusPublisher,
            Consumer<TtsPlaybackState> playbackStatePublisher,
            Consumer<TtsRequestStatusPayload> requestStatusPublisher
    ) {
        this.env = env;
        this.executorManager = executorManager;
        this.synthesisEngine = synthesisEngine;
        this.synthesisScheduler = new TtsSynthesisScheduler(executorManager, synthesisEngine);
        this.synthesisTaskCoordinator = new TtsSynthesisTaskCoordinator(synthesisEngine, synthesisScheduler, synthesisPolicy, lastFailure::set);
        this.modelLifecycleCoordinator = new TtsModelLifecycleCoordinator(executorManager, synthesisEngine, lastFailure::set);
        this.playbackController = new TtsPlaybackController(audioBridge, env, this, executorManager);
        this.sessionStatusPublisher = sessionStatusPublisher == null ? ignored -> {} : sessionStatusPublisher;
        this.playbackStatePublisher = playbackStatePublisher == null ? ignored -> {} : playbackStatePublisher;
        this.requestStatusPublisher = requestStatusPublisher == null ? ignored -> { } : requestStatusPublisher;
    }

    public TtsOperationResult prepare(Consumer<Boolean> completion) {
        return prepare(TtsVoiceProfile.defaults(), completion);
    }

    public TtsOperationResult prepare(TtsVoiceProfile voiceProfile, Consumer<Boolean> completion) {
        running.set(true);
        publishPlaybackState();
        return modelLifecycleCoordinator.prepare(voiceProfile, initialized -> {
            publishPlaybackState();
            if (completion != null) {
                completion.accept(initialized);
            }
        });
    }

    public void start() {
        running.set(true);
        publishPlaybackState();
    }

    public void stop() {
        running.set(false);
        speechInputAssembler.clear();
        speechSessionCoordinator.clear();
        publishSpeechTerminations();
        speechContexts.clear();
        sentenceWorks.clear();
        workSessions.clear();
        droppedStreams.clear();
        synthesisTaskCoordinator.cancelAll("runtime stopped");
        List<TtsSession> cancelled = sessionManager.cancelAll("runtime stopped");
        cancelled.forEach(sessionStatusPublisher);
        synthesisEngine.interrupt();
        playbackController.stopAll("runtime stopped");
        playbackBufferTracker.clear();
        publishPlaybackState();
    }

    public void destroy() {
        stop();
        sessionManager.clear();
        modelLifecycleCoordinator.shutdown();
    }

    public boolean isReady() {
        return running.get() && synthesisEngine.isInitialized();
    }

    public ExecutionLane synthesisLane() {
        return synthesisScheduler.lane();
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
                request == null ? "" : request.source().value(),
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
        if (request == null) {
            return reject(TtsFailureCode.INVALID_REQUEST, "TTS request is invalid", onFailure);
        }
        TtsSpeechSessionKey key = TtsSpeechSessionKey.of(
                request.source().value(),
                0L,
                0,
                request.requestId()
        );
        return submitSpeech(key, TtsTextInputMode.DOCUMENT, true, request, onComplete, onFailure);
    }

    public TtsOperationResult submitSpeech(
            TtsSpeechSessionKey key,
            TtsTextInputMode inputMode,
            boolean end,
            TtsRequest request
    ) {
        return submitSpeech(key, inputMode, end, request, null, null);
    }

    private TtsOperationResult submitSpeech(
            TtsSpeechSessionKey key,
            TtsTextInputMode inputMode,
            boolean end,
            TtsRequest request,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        if (!running.get()) {
            return reject(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not running", onFailure);
        }
        if (key == null || request == null || request.text() == null) {
            return reject(TtsFailureCode.INVALID_REQUEST, "TTS speech request is invalid", onFailure);
        }
        if (!modelLifecycleCoordinator.allowsSynthesis(request)) {
            return reject(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS model lifecycle is busy", onFailure);
        }
        if (droppedStreams.contains(key)) {
            if (end) {
                droppedStreams.remove(key);
                speechSessionCoordinator.end(key);
            }
            return TtsOperationResult.accepted(request.requestId());
        }

        TtsSpeechInputAssembler.Batch batch;
        try {
            batch = speechInputAssembler.accept(key, inputMode, request.text(), end);
        } catch (IllegalArgumentException exception) {
            return reject(TtsFailureCode.INVALID_REQUEST, exception.getMessage(), onFailure);
        }

        if (batch.opened()) {
            TtsSpeechSessionCoordinator.Admission admission = speechSessionCoordinator.admit(
                    key,
                    placementOf(request.playbackPolicy()),
                    request.priority()
            );
            if (admission.state() == TtsSpeechSessionCoordinator.AdmissionState.REJECTED) {
                speechInputAssembler.cancel(key);
                return reject(TtsFailureCode.QUEUE_FULL, "TTS speech session queue is full", onFailure);
            }
            if (admission.state() == TtsSpeechSessionCoordinator.AdmissionState.DROPPED) {
                droppedStreams.add(key);
                speechInputAssembler.cancel(key);
                publishRequestStatus(key, request, TtsRequestStatus.CANCELLED, TtsFailureCode.CANCELLED.name());
                if (batch.ended()) {
                    droppedStreams.remove(key);
                    speechSessionCoordinator.end(key);
                }
                return TtsOperationResult.accepted(request.requestId());
            }
            if (admission.state() == TtsSpeechSessionCoordinator.AdmissionState.ACCEPTED) {
                speechContexts.put(key, new SpeechContext(request, onComplete, onFailure));
                publishRequestStatus(key, request, TtsRequestStatus.QUEUED, "");
            }
            cancelSentenceWork(admission.cancelledWork(), "interrupted by " + request.requestId());
        }

        for (String sentence : batch.sentences()) {
            String normalized = normalizer.normalize(sentence);
            if (!normalized.isBlank()) {
                speechSessionCoordinator.appendSentence(key, normalized);
            }
        }
        if (batch.ended()) {
            speechSessionCoordinator.end(key);
        }
        publishSpeechTerminations();
        scheduleNextSpeechSentence();
        return TtsOperationResult.accepted(request.requestId());
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
        if (!modelLifecycleCoordinator.allowsSynthesis(request)) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS model lifecycle is busy");
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
                request.voiceProfile()
        );
        return synthesisTaskCoordinator.submit(normalizedRequest, streaming, ttlMillis, onAudio, onComplete, onFailure);
    }

    public TtsOperationResult synthesize(TtsRequest request, boolean streaming, TtsAudioChunkConsumer onAudio, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        return synthesize(request, streaming, 30_000L, onAudio, onComplete, onFailure);
    }

    public TtsControlResult stopAll(String reason) {
        int synthesisTasks = synthesisTaskCoordinator.cancelAll(reason);
        int speechSessions = speechContexts.size();
        speechInputAssembler.clear();
        speechSessionCoordinator.clear();
        publishSpeechTerminations();
        speechContexts.clear();
        sentenceWorks.clear();
        workSessions.clear();
        droppedStreams.clear();
        List<TtsSession> cancelled = sessionManager.cancelAll(reason);
        cancelled.forEach(sessionStatusPublisher);
        synthesisEngine.interrupt();
        playbackController.stopAll(reason);
        playbackBufferTracker.clear();
        publishPlaybackState();
        return TtsControlResult.accepted(TtsControlAction.STOP_ALL, speechSessions + synthesisTasks);
    }

    public TtsControlResult interruptActive(String reason) {
        TtsSpeechSessionKey key = speechSessionCoordinator.activeKey().orElse(null);
        if (key == null) {
            publishPlaybackState();
            return TtsControlResult.accepted(TtsControlAction.STOP_CURRENT, 0);
        }
        cancelSpeechSession(key, reason);
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
        int cancelledSynthesisTasks = synthesisTaskCoordinator.stopRequest(requestId, reason);
        String normalized = requestId.trim();
        String groupPrefix = normalized.endsWith(":") ? normalized : normalized + ":";
        List<TtsSpeechSessionKey> matching = speechContexts.entrySet().stream()
                .filter(entry -> {
                    TtsRequest request = entry.getValue().request;
                    return request.requestId().equals(normalized)
                            || request.requestId().startsWith(groupPrefix)
                            || request.groupId().equals(normalized)
                            || request.groupId().startsWith(groupPrefix);
                })
                .map(Map.Entry::getKey)
                .toList();
        matching.forEach(key -> cancelSpeechSession(key, reason));
        if (matching.isEmpty() && cancelledSynthesisTasks == 0) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.REQUEST_NOT_FOUND, "TTS request not found: " + requestId.trim());
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_REQUEST, failure);
        }
        return TtsControlResult.accepted(TtsControlAction.STOP_REQUEST, matching.size() + cancelledSynthesisTasks);
    }

    public TtsControlResult stopSource(TtsRequestSource source, String reason) {
        if (source == null || source.equals(TtsRequestSource.UNKNOWN)) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS source is invalid");
            lastFailure.set(failure);
            return TtsControlResult.rejected(TtsControlAction.STOP_SOURCE, failure);
        }
        List<TtsSpeechSessionKey> matching = speechContexts.entrySet().stream()
                .filter(entry -> entry.getValue().request.source().equals(source))
                .map(Map.Entry::getKey)
                .toList();
        matching.forEach(key -> cancelSpeechSession(key, reason));
        return TtsControlResult.accepted(TtsControlAction.STOP_SOURCE, matching.size());
    }

    public TtsControlResult reloadModel() {
        TtsControlResult stopResult = stopAll("model reload");
        TtsOperationResult submitted = modelLifecycleCoordinator.reload(null);
        return submitted.accepted()
                ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions())
                : TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, submitted.failure());
    }

    public TtsOperationResult reloadModel(Consumer<TtsControlResult> completion) {
        TtsControlResult stopResult = stopAll("model reload");
        return modelLifecycleCoordinator.reload(result -> {
            TtsControlResult completed = result.accepted()
                    ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions())
                    : result;
            if (completion != null) {
                completion.accept(completed);
            }
        });
    }

    public TtsControlResult useModel(String modelName) {
        TtsControlResult stopResult = stopAll("model switch");
        TtsOperationResult submitted = modelLifecycleCoordinator.useModel(modelName, null);
        return submitted.accepted()
                ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions())
                : TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, submitted.failure());
    }

    public TtsOperationResult useModel(String modelName, Consumer<TtsControlResult> completion) {
        TtsControlResult stopResult = stopAll("model switch");
        return modelLifecycleCoordinator.useModel(modelName, result -> {
            TtsControlResult completed = result.accepted()
                    ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, stopResult.affectedSessions())
                    : result;
            if (completion != null) {
                completion.accept(completed);
            }
        });
    }

    public TtsOperationResult previewWithModel(
            String previewModel,
            String restoreModel,
            TtsRequest request,
            Runnable onComplete,
            Consumer<TtsFailure> onFailure
    ) {
        if (request == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS preview request is invalid");
            fail(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        AtomicBoolean terminalCallback = new AtomicBoolean(false);
        Consumer<TtsFailure> failOnce = failure -> {
            if (terminalCallback.compareAndSet(false, true)) {
                fail(onFailure, failure);
            }
        };
        Runnable completeOnce = () -> {
            if (terminalCallback.compareAndSet(false, true)) {
                complete(onComplete);
            }
        };
        return modelLifecycleCoordinator.beginExclusivePreview(
                request.requestId(),
                previewModel,
                restoreModel,
                () -> stopAll("model preview"),
                () -> submit(
                        request,
                        () -> modelLifecycleCoordinator.finishExclusivePreview(request.requestId(), completeOnce, failOnce),
                        synthesisFailure -> modelLifecycleCoordinator.finishExclusivePreview(
                                request.requestId(),
                                () -> failOnce.accept(synthesisFailure),
                                failOnce
                        )
                ),
                failOnce
        );
    }

    @Override
    public void onPlaybackFinished(TtsSession session) {
        if (session == null) {
            return;
        }
        TtsSpeechSessionCoordinator.SentenceWork work = sentenceWorks.remove(session);
        if (work != null) {
            workSessions.remove(work, session);
        }
        if (session.isTerminal()) {
            playbackController.clearIfActive(session);
            scheduleNextSpeechSentence();
            return;
        }
        sessionManager.complete(session);
        sessionStatusPublisher.accept(session);
        playbackController.clearIfActive(session);
        if (!playbackController.isBusy()) {
            playbackBufferTracker.clear();
        }
        speechSessionCoordinator.complete(work);
        publishSpeechTerminations();
        publishPlaybackState();
        scheduleNextSpeechSentence();
    }

    private void scheduleNextSpeechSentence() {
        if (!running.get()) {
            return;
        }
        Optional<TtsSpeechSessionCoordinator.SentenceWork> next = speechSessionCoordinator.poll();
        publishSpeechTerminations();
        if (next.isEmpty()) {
            return;
        }
        TtsSpeechSessionCoordinator.SentenceWork work = next.get();
        SpeechContext context = speechContexts.get(work.sessionKey());
        if (context == null) {
            speechSessionCoordinator.complete(work);
            publishSpeechTerminations();
            scheduleNextSpeechSentence();
            return;
        }
        TtsRequest sentenceRequest = withText(context.request, work.text());
        TtsSession session = sessionManager.create(sentenceRequest);
        sentenceWorks.put(session, work);
        workSessions.put(work, session);
        transition(session, TtsSessionState.QUEUED);
        playbackController.enqueue(session);
        ProtocolTaskHandle handle = synthesisScheduler.submit(
                sentenceRequest,
                work,
                () -> runSpeechSentence(session, work, context)
        );
        if (handle.state() == ProtocolTaskState.REJECTED) {
            playbackController.removeQueued(session);
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS synthesis queue is full");
            failSpeechSession(session, work, context, failure);
        }
    }

    private void runSpeechSentence(
            TtsSession session,
            TtsSpeechSessionCoordinator.SentenceWork work,
            SpeechContext context
    ) {
        if (!running.get() || session.isTerminal()) {
            return;
        }
        try {
            sessionManager.activate(session);
            if (session.isTerminal()) {
                return;
            }
            transition(session, TtsSessionState.SYNTHESIZING);
            if (!synthesisEngine.initialize()) {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine is unavailable");
                failSpeechSession(session, work, context, failure);
                return;
            }
            int sampleRate = synthesisEngine.sampleRate();
            TtsSynthesisMode mode = synthesisPolicy.decide(synthesisEngine.backendSnapshot(), session.request(), playbackBufferTracker.estimate());
            boolean playbackAlreadyBusy = playbackController.activeSession() != null;
            playbackController.begin(session, sampleRate);
            publishRequestStatus(work.sessionKey(), context.request, TtsRequestStatus.PLAYING, "");
            if (!playbackAlreadyBusy) {
                playbackBufferTracker.begin(sampleRate);
            }
            sessionStatusPublisher.accept(session);
            publishPlaybackState();
            synthesisEngine.synthesize(session.request(), new RuntimeAudioSink(session, mode));
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.tts",
                    "SYNTHESIS_COMPLETED",
                    DiagnosticSeverity.INFO,
                    DiagnosticPrivacy.RAW_CONTENT,
                    Map.of("requestId", session.request().requestId(), "text", session.request().text())
            ));
            if (!session.isTerminal()) {
                playbackController.finish(session);
                sessionStatusPublisher.accept(session);
            }
        } catch (Throwable t) {
            TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, t);
            failSpeechSession(session, work, context, failure);
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.tts",
                    "SYNTHESIS_FAILED",
                    DiagnosticSeverity.ERROR,
                    DiagnosticPrivacy.RAW_CONTENT,
                    Map.of(
                            "requestId", session.request().requestId(),
                            "text", session.request().text(),
                            "failureCode", failure.code().name()
                    )
            ));
            env.error("tts.session.failed: " + session.request().requestId(), t);
            playbackController.stopActive("session failed");
            playbackBufferTracker.clear();
        }
    }

    private void failSpeechSession(
            TtsSession session,
            TtsSpeechSessionCoordinator.SentenceWork work,
            SpeechContext context,
            TtsFailure failure
    ) {
        failSession(session, failure);
        sentenceWorks.remove(session);
        workSessions.remove(work, session);
        playbackController.cancel(session, failure.message());
        speechInputAssembler.cancel(work.sessionKey());
        speechContexts.remove(work.sessionKey(), context);
        publishRequestStatus(
                work.sessionKey(),
                context.request,
                TtsRequestStatus.FAILED,
                failure.code().name()
        );
        fail(context.onFailure, failure);
        speechSessionCoordinator.cancel(work.sessionKey());
        speechSessionCoordinator.drainTerminations();
        scheduleNextSpeechSentence();
    }

    private void cancelSentenceWork(TtsSpeechSessionCoordinator.SentenceWork work, String reason) {
        if (work == null) {
            return;
        }
        TtsSession session = workSessions.remove(work);
        if (session == null) {
            return;
        }
        sentenceWorks.remove(session);
        sessionManager.cancel(session.request().requestId(), reason);
        playbackController.cancel(session, reason);
        sessionStatusPublisher.accept(session);
        synthesisScheduler.interrupt(work);
        playbackBufferTracker.clear();
        publishPlaybackState();
    }

    private void cancelSpeechSession(TtsSpeechSessionKey key, String reason) {
        if (key == null) {
            return;
        }
        boolean openStream = speechInputAssembler.isOpen(key);
        speechInputAssembler.cancel(key);
        if (openStream) {
            droppedStreams.add(key);
        } else {
            droppedStreams.remove(key);
        }
        workSessions.entrySet().stream()
                .filter(entry -> entry.getKey().sessionKey().equals(key))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(work -> cancelSentenceWork(work, reason));
        speechSessionCoordinator.cancel(key);
        publishSpeechTerminations();
        scheduleNextSpeechSentence();
    }

    private void publishSpeechTerminations() {
        for (TtsSpeechSessionCoordinator.Termination termination : speechSessionCoordinator.drainTerminations()) {
            if (termination.reason() == TtsSpeechSessionCoordinator.TerminationReason.CANCELLED) {
                boolean openStream = speechInputAssembler.isOpen(termination.sessionKey());
                speechInputAssembler.cancel(termination.sessionKey());
                if (openStream) {
                    droppedStreams.add(termination.sessionKey());
                } else {
                    droppedStreams.remove(termination.sessionKey());
                }
            }
            SpeechContext context = speechContexts.remove(termination.sessionKey());
            if (context == null) {
                continue;
            }
            if (termination.reason() == TtsSpeechSessionCoordinator.TerminationReason.COMPLETED) {
                publishRequestStatus(termination.sessionKey(), context.request, TtsRequestStatus.COMPLETED, "");
                complete(context.onComplete);
            } else {
                TtsFailure failure = TtsFailure.of(TtsFailureCode.CANCELLED, "TTS speech session cancelled");
                publishRequestStatus(
                        termination.sessionKey(),
                        context.request,
                        TtsRequestStatus.CANCELLED,
                        failure.code().name()
                );
                fail(context.onFailure, failure);
            }
        }
    }

    private void publishRequestStatus(
            TtsSpeechSessionKey key,
            TtsRequest request,
            TtsRequestStatus status,
            String failureCode
    ) {
        if (key == null || request == null || status == null) {
            return;
        }
        SpeechContext context = speechContexts.get(key);
        if (context != null && !context.transition(status)) {
            return;
        }
        requestStatusPublisher.accept(TtsRequestStatusPayload.now(
                request.requestId(),
                key.sourceId(),
                key.sessionId(),
                key.turnId(),
                status,
                failureCode
        ));
    }

    private TtsOperationResult reject(
            TtsFailureCode code,
            String message,
            Consumer<TtsFailure> onFailure
    ) {
        TtsFailure failure = TtsFailure.of(code, message);
        lastFailure.set(failure);
        fail(onFailure, failure);
        return TtsOperationResult.rejected(failure);
    }

    private static TtsPlaybackPlacement placementOf(TtsPlaybackPolicy policy) {
        if (policy == null) {
            return TtsPlaybackPlacement.QUEUE_AFTER_SESSION;
        }
        return switch (policy) {
            case DROP_IF_BUSY -> TtsPlaybackPlacement.DROP_IF_BUSY;
            case QUEUE -> TtsPlaybackPlacement.QUEUE_AFTER_SESSION;
            case INSERT_AFTER_SESSION -> TtsPlaybackPlacement.INSERT_AFTER_SESSION;
            case INSERT_AFTER_SENTENCE -> TtsPlaybackPlacement.INSERT_AFTER_SENTENCE;
            case CANCEL_SENTENCE_AND_PLAY -> TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY;
            case CANCEL_SESSION_AND_PLAY, REPLACE_CURRENT, LATEST_ONLY -> TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY;
        };
    }

    private static TtsRequest withText(TtsRequest request, String text) {
        return new TtsRequest(
                request.requestId(),
                request.groupId(),
                request.envelopeId(),
                request.traceId(),
                text,
                request.source(),
                request.playbackPolicy(),
                request.priority(),
                request.voiceProfile()
        );
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
        return request.source().equals(TtsRequestSource.ALERT)
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

    private static final class SpeechContext {
        private final TtsRequest request;
        private final Runnable onComplete;
        private final Consumer<TtsFailure> onFailure;
        private TtsRequestStatus lastStatus;

        private SpeechContext(TtsRequest request, Runnable onComplete, Consumer<TtsFailure> onFailure) {
            this.request = request;
            this.onComplete = onComplete;
            this.onFailure = onFailure;
        }

        private synchronized boolean transition(TtsRequestStatus next) {
            if (next == lastStatus) {
                return false;
            }
            if (lastStatus == TtsRequestStatus.COMPLETED
                    || lastStatus == TtsRequestStatus.CANCELLED
                    || lastStatus == TtsRequestStatus.FAILED) {
                return false;
            }
            lastStatus = next;
            return true;
        }
    }
}
