package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.synthesis.TtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.function.Consumer;
import java.util.function.Supplier;

final class TtsModelLifecycleCoordinator {
    private static final String MODULE_ID = "module.tts";
    private static final String CONCURRENCY_KEY = MODULE_ID + ":model-lifecycle";

    private final ProtocolExecutorManager executorManager;
    private final TtsSynthesisEngine synthesisEngine;
    private final Consumer<TtsFailure> failureObserver;
    private LifecycleState state = LifecycleState.IDLE;
    private String previewRequestId = "";
    private String previewRestoreModel = "";
    private boolean previewRestoreScheduled;

    TtsModelLifecycleCoordinator(ProtocolExecutorManager executorManager, TtsSynthesisEngine synthesisEngine,
                                 Consumer<TtsFailure> failureObserver) {
        this.executorManager = executorManager;
        this.synthesisEngine = synthesisEngine;
        this.failureObserver = failureObserver == null ? ignored -> { } : failureObserver;
    }

    TtsOperationResult prepare(Consumer<Boolean> completion) {
        return submitExclusiveOperation("prepare", synthesisEngine::initialize, ignored -> false, completion);
    }

    TtsOperationResult reload(Consumer<TtsControlResult> completion) {
        return submitExclusiveOperation("reload", () -> {
            synthesisEngine.shutdown();
            boolean initialized = synthesisEngine.initialize();
            return initialized
                    ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, 0)
                    : TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL,
                    TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine reload failed"));
        }, failure -> TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, failure), completion);
    }

    TtsOperationResult useModel(String modelName, Consumer<TtsControlResult> completion) {
        return submitExclusiveOperation("use-model", () -> {
            boolean initialized;
            if (modelName == null || modelName.isBlank()) {
                synthesisEngine.clearModel();
                initialized = true;
            } else {
                initialized = synthesisEngine.useModel(modelName);
            }
            return initialized
                    ? TtsControlResult.accepted(TtsControlAction.RELOAD_MODEL, 0)
                    : TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL,
                    TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS synthesis engine model switch failed"));
        }, failure -> TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, failure), completion);
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
        ProtocolTaskHandle handle = executorManager.submit(taskSpec("preview-begin"), () -> {
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
        });
        if (handle.state() == ProtocolTaskState.REJECTED) {
            clearPreviewState();
            TtsFailure rejected = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
            failureObserver.accept(rejected);
            notifyFailure(failure, rejected);
            return TtsOperationResult.rejected(rejected);
        }
        return TtsOperationResult.accepted(previewRequestId);
    }

    synchronized void finishExclusivePreview(String requestId, Runnable onRestored, Consumer<TtsFailure> failure) {
        if (state != LifecycleState.PREVIEW || previewRestoreScheduled || !previewRequestId.equals(requestId == null ? "" : requestId)) {
            return;
        }
        previewRestoreScheduled = true;
        String restoreModel = previewRestoreModel;
        ProtocolTaskHandle handle = executorManager.submit(taskSpec("preview-restore"), () -> {
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
        });
        if (handle.state() == ProtocolTaskState.REJECTED) {
            clearPreviewState();
            TtsFailure rejected = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
            failureObserver.accept(rejected);
            notifyFailure(failure, rejected);
        }
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
        state = LifecycleState.SHUTDOWN;
        previewRequestId = "";
        previewRestoreModel = "";
        previewRestoreScheduled = false;
        ProtocolTaskState taskState = executorManager.submit(taskSpec("shutdown"), synthesisEngine::shutdown).state();
        if (taskState == ProtocolTaskState.REJECTED) {
            failureObserver.accept(TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE,
                    "TTS model shutdown task was rejected"));
        }
    }

    private synchronized <T> TtsOperationResult submitExclusiveOperation(
            String action,
            Supplier<T> operation,
            java.util.function.Function<TtsFailure, T> failureResult,
            Consumer<T> completion
    ) {
        if (state != LifecycleState.IDLE) {
            return rejectBusy(null);
        }
        state = LifecycleState.OPERATION;
        ProtocolTaskHandle handle = executorManager.submit(taskSpec(action), () -> {
            T result;
            try {
                result = operation.get();
            } catch (Throwable throwable) {
                TtsFailure failure = TtsRuntimeFailurePolicy.classify(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, throwable);
                failureObserver.accept(failure);
                result = failureResult.apply(failure);
            } finally {
                synchronized (TtsModelLifecycleCoordinator.this) {
                    if (state == LifecycleState.OPERATION) {
                        state = LifecycleState.IDLE;
                    }
                }
            }
            notifyCompletion(completion, result);
        });
        if (handle.state() == ProtocolTaskState.REJECTED) {
            state = LifecycleState.IDLE;
            TtsFailure failure = TtsFailure.of(TtsFailureCode.QUEUE_FULL, "TTS model lifecycle queue is full");
            failureObserver.accept(failure);
            return TtsOperationResult.rejected(failure);
        }
        return TtsOperationResult.accepted("tts-model:" + action);
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
        clearPreviewState();
        failureObserver.accept(failure);
        notifyFailure(consumer, failure);
    }

    private void clearPreviewState() {
        state = LifecycleState.IDLE;
        previewRequestId = "";
        previewRestoreModel = "";
        previewRestoreScheduled = false;
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

    private static <T> void notifyCompletion(Consumer<T> completion, T value) {
        if (completion != null) {
            completion.accept(value);
        }
    }

    private static void notifyFailure(Consumer<TtsFailure> failure, TtsFailure value) {
        if (failure != null) {
            failure.accept(value);
        }
    }

    private enum LifecycleState {
        IDLE,
        OPERATION,
        PREVIEW,
        SHUTDOWN
    }
}
