package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.function.tts.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsModelSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntime;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntimeSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.protocol.Priority;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TtsModuleService {
    private final AtomicReference<TtsRuntime> runtime = new AtomicReference<>();
    private final AtomicReference<TtsModelService> modelService = new AtomicReference<>();
    private final Object previewModelLock = new Object();

    public void bindRuntime(TtsRuntime ttsRuntime) {
        runtime.set(Objects.requireNonNull(ttsRuntime));
    }

    public void bindModelService(TtsModelService service) {
        modelService.set(Objects.requireNonNull(service));
    }

    public void unbindModelService(TtsModelService service) {
        modelService.compareAndSet(service, null);
    }

    public void unbindRuntime(TtsRuntime ttsRuntime) {
        runtime.compareAndSet(ttsRuntime, null);
    }

    public boolean ready() {
        TtsRuntime current = runtime.get();
        return current != null && current.isReady();
    }

    public TtsRuntimeSnapshot snapshot() {
        TtsRuntime current = runtime.get();
        return current == null ? TtsRuntimeSnapshot.unbound() : current.snapshot();
    }

    public TtsModelSnapshot modelSnapshot() {
        TtsModelService current = modelService.get();
        return current == null ? TtsModelSnapshot.unconfigured() : current.snapshot();
    }

    public TtsBackendSnapshot backendSnapshot() {
        TtsRuntime current = runtime.get();
        return current == null ? TtsBackendSnapshot.unavailable() : current.backendSnapshot();
    }

    public TtsOperationResult preview(String text, TtsVoiceProfile voiceProfile, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        TtsRuntime current = runtime.get();
        if (current == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not available");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (text == null || text.isBlank()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.EMPTY_TEXT, "TTS preview text is empty");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        return submitPreview(current, text, voiceProfile, onComplete, onFailure);
    }

    public TtsOperationResult previewDraftModel(String modelName, String text, TtsVoiceProfile voiceProfile, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        TtsRuntime current = runtime.get();
        TtsModelService models = modelService.get();
        if (current == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not available");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (models == null) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS model service is not available");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (modelName == null || modelName.isBlank()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.INVALID_REQUEST, "TTS draft model is empty");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        if (text == null || text.isBlank()) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.EMPTY_TEXT, "TTS preview text is empty");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        TtsModelInfo draft = models.findModelByName(modelName);
        if (draft == null || !models.hasModelContent(draft)) {
            TtsFailure failure = TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS draft model is not installed");
            notifyFailure(onFailure, failure);
            return TtsOperationResult.rejected(failure);
        }
        synchronized (previewModelLock) {
            String originalModel = models.currentConfiguredModelName();
            TtsControlResult switchResult = current.useModel(draft.name);
            if (!switchResult.accepted()) {
                TtsFailure failure = switchResult.failure() == null
                        ? TtsFailure.of(TtsFailureCode.SYNTHESIS_ENGINE_UNAVAILABLE, "TTS draft model reload failed")
                        : switchResult.failure();
                notifyFailure(onFailure, failure);
                return TtsOperationResult.rejected(failure);
            }
            TtsOperationResult result = submitPreviewWithRestore(current, originalModel, text, voiceProfile, onComplete, onFailure);
            if (!result.accepted()) {
                restoreModelAfterPreview(current, originalModel, null);
            }
            return result;
        }
    }

    private TtsOperationResult submitPreviewWithRestore(TtsRuntime current, String originalModel, String text, TtsVoiceProfile voiceProfile, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        String requestId = "tts-preview:" + UUID.randomUUID();
        TtsRequest request = new TtsRequest(
                requestId,
                requestId,
                requestId,
                requestId,
                text,
                TtsRequestSource.PREVIEW,
                TtsPlaybackPolicy.REPLACE_CURRENT,
                Priority.NORMAL,
                voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile,
                false
        );
        return current.submitWithModel(originalModel, request, onComplete, onFailure);
    }

    private TtsOperationResult submitPreview(TtsRuntime current, String text, TtsVoiceProfile voiceProfile, Runnable onComplete, Consumer<TtsFailure> onFailure) {
        String requestId = "tts-preview:" + UUID.randomUUID();
        TtsRequest request = new TtsRequest(
                requestId,
                requestId,
                requestId,
                requestId,
                text,
                TtsRequestSource.PREVIEW,
                TtsPlaybackPolicy.REPLACE_CURRENT,
                Priority.NORMAL,
                voiceProfile == null ? TtsVoiceProfile.defaults() : voiceProfile,
                false
        );
        return current.submit(request, onComplete == null ? () -> {} : onComplete, onFailure == null ? ignored -> {} : onFailure);
    }

    private void restoreModelAfterPreview(TtsRuntime current, String originalModel, Runnable onRestored) {
        synchronized (previewModelLock) {
            try {
                if (originalModel != null && !originalModel.isBlank()) {
                    current.useModel(originalModel);
                }
            } finally {
                if (onRestored != null) {
                    onRestored.run();
                }
            }
        }
    }

    public TtsControlResult stopPreview(String reason) {
        TtsRuntime current = runtime.get();
        if (current == null) {
            return runtimeUnavailable(TtsControlAction.STOP_PREVIEW);
        }
        TtsControlResult result = current.stopSource(TtsRequestSource.PREVIEW, reason == null || reason.isBlank() ? "preview stopped" : reason);
        return result.accepted()
                ? TtsControlResult.accepted(TtsControlAction.STOP_PREVIEW, result.affectedSessions())
                : TtsControlResult.rejected(TtsControlAction.STOP_PREVIEW, result.failure());
    }

    public TtsControlResult stopAll(String reason) {
        TtsRuntime current = runtime.get();
        if (current == null) {
            return runtimeUnavailable(TtsControlAction.STOP_ALL);
        }
        return current.stopAll(reason == null || reason.isBlank() ? "tts stopped" : reason);
    }

    public TtsControlResult stopCurrent(String reason) {
        TtsRuntime current = runtime.get();
        if (current == null) {
            return runtimeUnavailable(TtsControlAction.STOP_CURRENT);
        }
        return current.stopCurrent(reason == null || reason.isBlank() ? "tts current stopped" : reason);
    }

    public TtsControlResult reloadModel() {
        TtsRuntime current = runtime.get();
        if (current == null) {
            return runtimeUnavailable(TtsControlAction.RELOAD_MODEL);
        }
        return current.reloadModel();
    }

    private TtsControlResult runtimeUnavailable(TtsControlAction action) {
        return TtsControlResult.rejected(action, TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not available"));
    }

    private void notifyFailure(Consumer<TtsFailure> onFailure, TtsFailure failure) {
        if (onFailure != null) {
            onFailure.accept(failure);
        }
    }
}
