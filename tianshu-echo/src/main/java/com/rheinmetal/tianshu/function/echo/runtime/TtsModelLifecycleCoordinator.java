package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleExecutionAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;

final class TtsModelLifecycleCoordinator {
    private static final String MODULE_ID = ProtocolModuleIds.TTS;
    private static final String CONCURRENCY_KEY = TtsSynthesisScheduler.BACKEND_CONCURRENCY_KEY;
    /** Queue action name; also what the gate reports to its observers while this load is parked. */
    private static final String LOAD_ACTION = "tts-model";
    /** Operation kinds of the model-load family: they coalesce with each other, never with prepare. */
    private static final String SWITCH_MODEL = "use-model";
    private static final String RELOAD_MODEL = "reload";

    private final ModuleExecutionAccess executorManager;
    private final TtsSynthesisEngine synthesisEngine;
    private final Consumer<TtsFailure> failureObserver;
    private final Consumer<Boolean> loadingObserver;
    /** Serial gate shared with the retrieval lane, or null when this module loads on its own. */
    private final NativeModelLoadQueue loadQueue;
    private LifecycleState state = LifecycleState.IDLE;
    private String previewRequestId = "";
    private String previewRestoreModel = "";
    private boolean previewRestoreScheduled;
    /** Terminal failure callback of the preview in flight; no-op when there is no preview. */
    private Consumer<TtsFailure> pendingPreviewFailure = ignored -> { };
    private final List<Consumer<Boolean>> prepareCompletions = new ArrayList<>();
    private TtsVoiceProfile requestedPrepareVoice = TtsVoiceProfile.defaults();
    private long prepareRevision;
    /** Kind of the exclusive model-load operation in flight; empty when there is none. */
    private String operationKind = "";
    private final List<Consumer<TtsControlResult>> switchCompletions = new ArrayList<>();
    /** Newest requested model of an in-flight switch; empty means "whatever the configuration selects". */
    private String requestedModelName = "";
    private boolean reloadRequested;
    private long switchRevision;

    TtsModelLifecycleCoordinator(ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine,
                                 Consumer<TtsFailure> failureObserver) {
        this(executorManager, synthesisEngine, failureObserver, null);
    }

    TtsModelLifecycleCoordinator(ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine,
                                 Consumer<TtsFailure> failureObserver, Consumer<Boolean> loadingObserver) {
        this(executorManager, synthesisEngine, failureObserver, loadingObserver, null);
    }

    TtsModelLifecycleCoordinator(ModuleExecutionAccess executorManager, TtsSynthesisEngine synthesisEngine,
                                 Consumer<TtsFailure> failureObserver, Consumer<Boolean> loadingObserver,
                                 NativeModelLoadQueue loadQueue) {
        this.executorManager = executorManager;
        this.synthesisEngine = synthesisEngine;
        this.failureObserver = failureObserver == null ? ignored -> { } : failureObserver;
        this.loadingObserver = loadingObserver == null ? ignored -> { } : loadingObserver;
        this.loadQueue = loadQueue;
    }

    TtsOperationResult prepare(Consumer<Boolean> completion) {
        return prepare(TtsVoiceProfile.defaults(), completion);
    }

    synchronized TtsOperationResult prepare(TtsVoiceProfile voiceProfile, Consumer<Boolean> completion) {
        if (state == LifecycleState.PREPARING) {
            requestedPrepareVoice = normalizedVoice(voiceProfile);
            prepareRevision++;
            addPrepareCompletion(completion);
            return TtsOperationResult.accepted("tts-model:prepare");
        }
        if (state != LifecycleState.IDLE) {
            return rejectBusy(null);
        }
        state = LifecycleState.PREPARING;
        requestedPrepareVoice = normalizedVoice(voiceProfile);
        prepareRevision++;
        addPrepareCompletion(completion);
        if (!requestManagedLoad("prepare", this::prepareNow, this::failPrepareQueueFull, true)) {
            // failPrepareQueueFull has already reset the state and answered every waiting completion.
            return TtsOperationResult.rejected(TtsFailure.of(TtsFailureCode.QUEUE_FULL,
                    "TTS model lifecycle queue is full"));
        }
        return TtsOperationResult.accepted("tts-model:prepare");
    }

    TtsOperationResult reload(Consumer<TtsControlResult> completion) {
        return requestModelSwitch(RELOAD_MODEL, "", true, completion);
    }

    TtsOperationResult useModel(String modelName, Consumer<TtsControlResult> completion) {
        return requestModelSwitch(SWITCH_MODEL, modelName == null ? "" : modelName.trim(), false, completion);
    }

    synchronized TtsOperationResult beginExclusivePreview(
            String requestId,
            String previewModel,
            String restoreModel,
            Runnable beforeSwitch,
            Supplier<TtsOperationResult> submitPreview,
            Consumer<TtsFailure> failure
    ) {
        if (state != LifecycleState.IDLE) {
            return rejectBusy(failure);
        }
        state = LifecycleState.PREVIEW;
        previewRequestId = requestId == null ? "" : requestId;
        previewRestoreModel = restoreModel == null ? "" : restoreModel;
        previewRestoreScheduled = false;
        // Held for the whole preview, because the switch may still be parked in the gate when the module
        // shuts down; shutdown answers this consumer instead of dropping it.
        pendingPreviewFailure = failure == null ? ignored -> { } : failure;
        boolean submitted = requestManagedLoad(
                "preview-begin",
                () -> runPreviewSwitch(beforeSwitch, previewModel, submitPreview, failure),
                () -> failPreviewQueueFull(failure),
                false
        );
        return submitted
                ? TtsOperationResult.accepted(previewRequestId)
                : TtsOperationResult.rejected(TtsFailure.of(TtsFailureCode.QUEUE_FULL,
                "TTS model lifecycle queue is full"));
    }

    synchronized void finishExclusivePreview(String requestId, Runnable onRestored, Consumer<TtsFailure> failure) {
        if (state != LifecycleState.PREVIEW || previewRestoreScheduled || !previewRequestId.equals(requestId == null ? "" : requestId)) {
            return;
        }
        previewRestoreScheduled = true;
        String restoreModel = previewRestoreModel;
        if (failure != null) {
            // The restore is the last stage of the same preview, so a shutdown that lands while it is parked
            // has to answer this consumer too.
            pendingPreviewFailure = failure;
        }
        // The restore is a native model switch too, so it takes the same managed path as the switch it
        // undoes; a preview that skipped the gate would be the second door all over again. A refusal is
        // answered by failPreviewQueueFull, so nothing else has to be done here.
        requestManagedLoad(
                "preview-restore",
                () -> runPreviewRestore(restoreModel, onRestored, failure),
                () -> failPreviewQueueFull(failure),
                false
        );
    }

    synchronized boolean allowsSynthesis(TtsRequest request) {
        if (state == LifecycleState.IDLE) {
            return true;
        }
        return state == LifecycleState.PREVIEW
                && request != null
                && previewRequestId.equals(request.requestId());
    }

    synchronized void shutdown() {
        List<Consumer<Boolean>> cancelledPrepares;
        List<Consumer<TtsControlResult>> cancelledSwitches;
        Consumer<TtsFailure> cancelledPreview;
        synchronized (this) {
            boolean previewPending = state == LifecycleState.PREVIEW;
            state = LifecycleState.SHUTDOWN;
            cancelledPrepares = drainPrepareCompletions();
            cancelledSwitches = drainSwitchCompletions();
            cancelledPreview = previewPending ? pendingPreviewFailure : null;
            pendingPreviewFailure = ignored -> { };
            previewRequestId = "";
            previewRestoreModel = "";
            previewRestoreScheduled = false;
            // A prepare or a switch still parked in the native-load gate never reaches its own release, so the
            // "loading" signal has to be dropped here or the HUD would wait for a load that will not happen.
            publishLoading(false);
            ProtocolTaskState taskState = executorManager.submit(taskSpec("shutdown"), synthesisEngine::shutdown).state();
            if (taskState == ProtocolTaskState.REJECTED) {
                failureObserver.accept(TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                        "TTS model shutdown task was rejected"));
            }
        }
        // Shutdown is terminal, so every caller still waiting on a model load is answered here. Dropping them
        // — which is what this method used to do — leaves a settings action or a preview waiting for an event
        // that can never arrive. CANCELLED is the closest existing reason: nothing failed to load, the module
        // went away.
        TtsFailure cancelled = TtsFailure.of(TtsFailureCode.CANCELLED, "TTS model lifecycle is shut down");
        notifyPrepareCompletions(cancelledPrepares, false);
        notifySwitchCompletions(cancelledSwitches,
                TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, cancelled));
        notifyFailure(cancelledPreview, cancelled);
    }

    // ---------------------------------------------------------------- the one managed load path

    /**
     * The only way a native model read starts here: ask the gate, then re-enter this module's own lane once
     * the permit is granted, run the load, hand the permit back in a {@code finally}.
     *
     * <p>Prepare, reload, model switch and both preview switches enter through this method — there is no
     * second door. An earlier version let reload and the model switch call the engine directly, so those
     * native peaks could add up with the embedding load the gate exists to serialise.</p>
     *
     * @param loadingSignal publish the "model is loading" pulse for this operation; the preview switches
     *                      deliberately do not, because a preview is not a load the player waits for
     * @return false when the work was refused outright, so the caller can answer with a rejection
     */
    private boolean requestManagedLoad(String action, Runnable load, Runnable refused, boolean loadingSignal) {
        if (loadQueue == null) {
            boolean submitted = submitManagedLoad(action, load, null, loadingSignal);
            if (!submitted) {
                refused.run();
            }
            return submitted;
        }
        NativeModelLoadQueue.Outcome outcome = loadQueue.request(MODULE_ID, LOAD_ACTION, () -> {
            if (!submitManagedLoad(action, load, loadQueue, loadingSignal)) {
                refused.run();
            }
        }, NativeModelLoadQueue.Tier.DEFERRED);
        if (outcome == NativeModelLoadQueue.Outcome.REFUSED) {
            // The attempt never entered the gate, so its tier must be settled here: the voice-input load
            // parked behind it would otherwise wait for a load that was never queued.
            loadQueue.settle(NativeModelLoadQueue.Tier.DEFERRED);
            refused.run();
            return false;
        }
        // While the request is parked the loading signal stays on: from the player's side the model is
        // still coming, it is just queued behind retrieval.
        if (loadingSignal) {
            publishLoading(true);
        }
        return true;
    }

    /** Second hop: a released request re-enters its own lane instead of loading on the releasing thread. */
    private boolean submitManagedLoad(String action, Runnable load, NativeModelLoadQueue queue, boolean loadingSignal) {
        ProtocolTaskHandle handle = executorManager.submit(taskSpec(action), () -> {
            if (loadingSignal) {
                publishLoading(true);
            }
            try {
                load.run();
            } finally {
                if (loadingSignal) {
                    publishLoading(false);
                }
                if (queue != null) {
                    queue.release(MODULE_ID);
                }
            }
        });
        if (handle.state() != ProtocolTaskState.REJECTED) {
            return true;
        }
        if (queue != null) {
            queue.release(MODULE_ID);
        }
        return false;
    }

    /**
     * The prepare work, running inside a managed load.
     *
     * <p>The loading pulse and the permit belong to {@link #submitManagedLoad} now, so this method only does
     * the work: a permit that outlived a failed load would park retrieval for the rest of the session, which
     * is why handing it back is not left to per-operation code.</p>
     */
    private void prepareNow() {
        PrepareOutcome outcome = null;
        try {
            if (!isPrepareWanted()) {
                // Stopped, superseded or shut down while the request was parked in the gate: the model
                // must not be read after the module that owns it is gone.
                notifyPrepareCompletions(drainPrepareCompletionsLocked(), false);
                return;
            }
            outcome = runPrepareWork();
        } catch (RuntimeException unexpected) {
            failureObserver.accept(TtsRuntimeFailurePolicy.classify(
                    TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                    unexpected
            ));
            outcome = completePrepare(false);
        }
        notifyPrepareCompletions(outcome.completions(), outcome.initialized());
    }

    private synchronized boolean isPrepareWanted() {
        return state == LifecycleState.PREPARING;
    }

    private synchronized List<Consumer<Boolean>> drainPrepareCompletionsLocked() {
        return drainPrepareCompletions();
    }

    private void failPrepareQueueFull() {
        List<Consumer<Boolean>> completions;
        synchronized (this) {
            if (state == LifecycleState.PREPARING) {
                state = LifecycleState.IDLE;
            }
            completions = drainPrepareCompletions();
        }
        TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL,
                "TTS model load queue is full");
        failureObserver.accept(failure);
        publishLoading(false);
        notifyPrepareCompletions(completions, false);
    }

    private PrepareOutcome runPrepareWork() {
        boolean initialized;
        try {
            initialized = synthesisEngine.initialize();
        } catch (Throwable throwable) {
            failureObserver.accept(TtsRuntimeFailurePolicy.classify(
                    TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                    throwable
            ));
            return completePrepare(false);
        }
        if (!initialized) {
            return completePrepare(false);
        }

        while (true) {
            TtsVoiceProfile voiceProfile;
            long revision;
            List<Consumer<Boolean>> stoppedCompletions = null;
            synchronized (this) {
                if (state != LifecycleState.PREPARING) {
                    stoppedCompletions = drainPrepareCompletions();
                    voiceProfile = null;
                    revision = -1L;
                } else {
                    voiceProfile = requestedPrepareVoice;
                    revision = prepareRevision;
                }
            }
            if (stoppedCompletions != null) {
                return new PrepareOutcome(stoppedCompletions, false);
            }

            boolean preloaded;
            try {
                preloaded = synthesisEngine.preloadVoice(voiceProfile);
            } catch (Throwable throwable) {
                failureObserver.accept(TtsRuntimeFailurePolicy.classify(
                        TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                        throwable
                ));
                preloaded = false;
            }

            List<Consumer<Boolean>> completions;
            synchronized (this) {
                if (state != LifecycleState.PREPARING) {
                    completions = drainPrepareCompletions();
                    preloaded = false;
                } else if (preloaded && revision != prepareRevision) {
                    continue;
                } else {
                    state = LifecycleState.IDLE;
                    completions = drainPrepareCompletions();
                }
            }
            return new PrepareOutcome(completions, preloaded);
        }
    }

    private PrepareOutcome completePrepare(boolean initialized) {
        List<Consumer<Boolean>> completions;
        synchronized (this) {
            if (state == LifecycleState.PREPARING) {
                state = LifecycleState.IDLE;
            }
            completions = drainPrepareCompletions();
        }
        return new PrepareOutcome(completions, initialized);
    }

    private void addPrepareCompletion(Consumer<Boolean> completion) {
        if (completion != null) {
            prepareCompletions.add(completion);
        }
    }

    private List<Consumer<Boolean>> drainPrepareCompletions() {
        List<Consumer<Boolean>> completions = List.copyOf(prepareCompletions);
        prepareCompletions.clear();
        return completions;
    }

    private static void notifyPrepareCompletions(List<Consumer<Boolean>> completions, boolean initialized) {
        completions.forEach(completion -> completion.accept(initialized));
    }

    private record PrepareOutcome(List<Consumer<Boolean>> completions, boolean initialized) {
    }

    // ---------------------------------------------------------------- the model-load family

    /**
     * Starts a reload or a model switch, or merges it into the one already in flight.
     *
     * <p>Merging rather than rejecting is what keeps a player who asks twice from getting a failure: the
     * newest request wins, every waiting caller is answered by the run that satisfies it, and the model is
     * loaded once per distinct target instead of once per request.</p>
     */
    private synchronized TtsOperationResult requestModelSwitch(
            String action,
            String modelName,
            boolean forceReload,
            Consumer<TtsControlResult> completion
    ) {
        if (state == LifecycleState.OPERATION && !operationKind.isEmpty()) {
            requestedModelName = modelName;
            reloadRequested = reloadRequested || forceReload;
            switchRevision++;
            addSwitchCompletion(completion);
            return TtsOperationResult.accepted("tts-model:" + action);
        }
        if (state != LifecycleState.IDLE) {
            return rejectBusy(null);
        }
        state = LifecycleState.OPERATION;
        operationKind = action;
        requestedModelName = modelName;
        reloadRequested = forceReload;
        switchRevision++;
        addSwitchCompletion(completion);
        TtsFailure refusal = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
        if (!requestManagedLoad(action, this::runModelSwitch, () -> failModelSwitch(action), true)) {
            return TtsOperationResult.rejected(refusal);
        }
        return TtsOperationResult.accepted("tts-model:" + action);
    }

    /**
     * Runs the switch inside a managed load, looping once more when a newer request arrived while loading.
     *
     * <p>The engine loads the replacement before it closes the running backend, so a failed switch leaves
     * the lane on the model it already had — the same rule the ASR hotword reload follows.</p>
     */
    private void runModelSwitch() {
        String action;
        synchronized (this) {
            if (state != LifecycleState.OPERATION) {
                // Cancelled by shutdown while the request was parked: nothing may load for a dead module,
                // and every waiting caller is answered with the failure that the caller's kind describes.
                String stoppedKind = operationKind;
                List<Consumer<TtsControlResult>> stopped = drainSwitchCompletionsLocked();
                notifySwitchCompletions(stopped,
                        TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, switchFailure(stoppedKind)));
                return;
            }
            action = operationKind;
        }
        while (true) {
            String target;
            boolean forceReload;
            long revision;
            synchronized (this) {
                target = requestedModelName;
                forceReload = reloadRequested;
                revision = switchRevision;
            }
            TtsFailure thrown = null;
            boolean switched;
            try {
                switched = switchTarget(target, forceReload);
            } catch (Throwable throwable) {
                thrown = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, throwable);
                switched = false;
            }
            if (thrown != null) {
                failureObserver.accept(thrown);
            }
            List<Consumer<TtsControlResult>> completions;
            synchronized (this) {
                boolean superseded = switched
                        && revision != switchRevision
                        && state == LifecycleState.OPERATION;
                if (superseded) {
                    continue;
                }
                if (state == LifecycleState.OPERATION) {
                    state = LifecycleState.IDLE;
                }
                operationKind = "";
                reloadRequested = false;
                completions = drainSwitchCompletions();
            }
            notifySwitchCompletions(completions, switched
                    ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, 0)
                    : TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL,
                    thrown != null ? thrown : switchFailure(action)));
            return;
        }
    }

    private boolean switchTarget(String modelName, boolean forceReload) {
        if (forceReload) {
            return synthesisEngine.reload();
        }
        if (modelName == null || modelName.isBlank()) {
            synthesisEngine.clearModel();
            return true;
        }
        return synthesisEngine.useModel(modelName);
    }

    private void failModelSwitch(String action) {
        List<Consumer<TtsControlResult>> completions;
        synchronized (this) {
            if (state == LifecycleState.OPERATION) {
                state = LifecycleState.IDLE;
            }
            operationKind = "";
            reloadRequested = false;
            completions = drainSwitchCompletions();
        }
        TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
        failureObserver.accept(failure);
        publishLoading(false);
        notifySwitchCompletions(completions, TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, failure));
    }

    private static TtsFailure switchFailure(String action) {
        return TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                RELOAD_MODEL.equals(action)
                        ? "TTS synthesis engine reload failed"
                        : "TTS synthesis engine model switch failed");
    }

    private void addSwitchCompletion(Consumer<TtsControlResult> completion) {
        if (completion != null) {
            switchCompletions.add(completion);
        }
    }

    private List<Consumer<TtsControlResult>> drainSwitchCompletions() {
        List<Consumer<TtsControlResult>> completions = List.copyOf(switchCompletions);
        switchCompletions.clear();
        return completions;
    }

    private List<Consumer<TtsControlResult>> drainSwitchCompletionsLocked() {
        if (state == LifecycleState.OPERATION) {
            state = LifecycleState.IDLE;
        }
        operationKind = "";
        reloadRequested = false;
        return drainSwitchCompletions();
    }

    private static void notifySwitchCompletions(List<Consumer<TtsControlResult>> completions, TtsControlResult result) {
        completions.forEach(completion -> completion.accept(result));
    }

    // ---------------------------------------------------------------- preview

    private void runPreviewSwitch(
            Runnable beforeSwitch,
            String previewModel,
            Supplier<TtsOperationResult> submitPreview,
            Consumer<TtsFailure> failure
    ) {
        synchronized (this) {
            if (state != LifecycleState.PREVIEW) {
                // Stopped while the switch was parked: nothing may be loaded for a dead preview.
                return;
            }
        }
        try {
            if (beforeSwitch != null) {
                beforeSwitch.run();
            }
            if (!synthesisEngine.useModel(previewModel)) {
                restorePreviewSafely();
                failPreview(TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS preview model reload failed"), failure);
                return;
            }
            TtsOperationResult result = submitPreview == null ? null : submitPreview.get();
            if (result == null || !result.accepted()) {
                TtsFailure previewFailure = result == null || result.failure() == null
                        ? TtsFailure.of(TtsFailureCode.SYNTHESIS_FAILED, "TTS preview submission failed")
                        : result.failure();
                restorePreviewSafely();
                failPreview(previewFailure, failure);
            }
        } catch (Throwable throwable) {
            TtsFailure previewFailure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_FAILED, throwable);
            restorePreviewSafely();
            failPreview(previewFailure, failure);
        }
    }

    private void runPreviewRestore(String restoreModel, Runnable onRestored, Consumer<TtsFailure> failure) {
        synchronized (this) {
            if (state != LifecycleState.PREVIEW) {
                // Stopped while the restore was parked: shutdown already answered this preview, so loading
                // the restore model here would both notify a second time and read a model for a dead module.
                return;
            }
        }
        TtsFailure restoreFailure = null;
        try {
            if (restoreModel.isBlank()) {
                synthesisEngine.clearModel();
            } else if (!synthesisEngine.useModel(restoreModel)) {
                restoreFailure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS preview model restore failed");
            }
        } catch (Throwable throwable) {
            restoreFailure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, throwable);
        } finally {
            synchronized (TtsModelLifecycleCoordinator.this) {
                clearPreviewState();
            }
        }
        if (restoreFailure != null) {
            failureObserver.accept(restoreFailure);
            notifyFailure(failure, restoreFailure);
        }
        if (onRestored != null) {
            onRestored.run();
        }
    }

    private void failPreviewQueueFull(Consumer<TtsFailure> failure) {
        synchronized (this) {
            clearPreviewState();
        }
        TtsFailure rejected = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
        failureObserver.accept(rejected);
        publishLoading(false);
        notifyFailure(failure, rejected);
    }

    // ---------------------------------------------------------------- shared helpers

    private static TtsVoiceProfile normalizedVoice(TtsVoiceProfile voiceProfile) {
        return voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile;
    }

    private void publishLoading(boolean loading) {
        try {
            loadingObserver.accept(loading);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized TtsOperationResult rejectBusy(Consumer<TtsFailure> failureConsumer) {
        TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS model lifecycle is busy");
        notifyFailure(failureConsumer, failure);
        return TtsOperationResult.rejected(failure);
    }

    private void restorePreviewSafely() {
        try {
            if (previewRestoreModel.isBlank()) {
                synthesisEngine.clearModel();
            } else if (!synthesisEngine.useModel(previewRestoreModel)) {
                failureObserver.accept(TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                        "TTS preview model restore failed"));
            }
        } catch (Throwable throwable) {
            failureObserver.accept(TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, throwable));
        }
    }

    private synchronized void failPreview(TtsFailure failure, Consumer<TtsFailure> consumer) {
        if (state != LifecycleState.PREVIEW) {
            // The preview already reached a terminal state — shutdown answered it, or a refusal did — so
            // answering again would give the waiter two outcomes for one request.
            return;
        }
        clearPreviewState();
        failureObserver.accept(failure);
        notifyFailure(consumer, failure);
    }

    private void clearPreviewState() {
        state = LifecycleState.IDLE;
        previewRequestId = "";
        previewRestoreModel = "";
        previewRestoreScheduled = false;
        // The preview is over: a later shutdown must not answer a consumer that was already answered.
        pendingPreviewFailure = ignored -> { };
    }

    private ProtocolTaskSpec taskSpec(String action) {
        return ProtocolTaskSpec.builder()
                .taskId("tts-model:" + action + ":" + System.nanoTime())
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.MODEL_LOAD)
                .priority(Priority.NORMAL)
                .concurrencyKey(CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(8)
                .build();
    }

    private static void notifyFailure(Consumer<TtsFailure> failure, TtsFailure value) {
        if (failure != null) {
            failure.accept(value);
        }
    }

    private enum LifecycleState {
        IDLE,
        PREPARING,
        OPERATION,
        PREVIEW,
        SHUTDOWN
    }
}
