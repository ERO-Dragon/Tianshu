package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntime;
import com.rheinmetal.tianshu.function.tts.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSpeechSessionKey;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.tts.synthesis.DefaultTtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.voice.TtsVoiceCloneProfile;
import com.rheinmetal.tianshu.function.tts.voice.TtsVoiceCloneRegistry;
import com.rheinmetal.tianshu.function.tts.voice.TtsVoiceRequestValidator;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.TtsAudioPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public final class TtsModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess runtime;
    private final IGameEnvironment env;
    private final TtsConfiguration config;
    private final TtsProtocolAdapter adapter;
    private TtsRuntime ttsRuntime;
    private TtsModuleService moduleService;
    private TtsModelService modelService;
    private VoiceNotificationService voiceNotificationService;
    private TtsVoiceLibraryService voiceLibraryService;
    private TtsVoiceCloneRegistry voiceCloneRegistry;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle delayedAutoLoad;
    private volatile boolean destroyed;

    public TtsModule(IAudioBridge audioBridge, ModuleRuntimeAccess runtime, IGameEnvironment env, TtsConfiguration config) {
        this.audioBridge = audioBridge;
        this.runtime = runtime;
        this.env = env;
        this.config = config;
        this.adapter = new TtsProtocolAdapter(runtime);
    }

    @Override
    public String moduleId() {
        return TtsProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        modelService = new TtsModelService(env, config, runtime, this::publishModuleStatus);
        moduleService = new TtsModuleService();
        moduleService.bindModelService(modelService);
        voiceNotificationService = new VoiceNotificationService(runtime);
        voiceLibraryService = new TtsVoiceLibraryService(env, config);
        voiceCloneRegistry = new TtsVoiceCloneRegistry(env, config);
        context.services().register(TtsModuleService.class, moduleService);
        context.services().register(TtsModelService.class, modelService);
        context.services().register(VoiceNotificationService.class, voiceNotificationService);
        context.services().register(TtsVoiceLibraryService.class, voiceLibraryService);
        context.services().register(TtsVoiceCloneRegistry.class, voiceCloneRegistry);
        adapter.registerSpeakCapability(this::handleSpeak);
        adapter.registerSynthesizeCapability(this::handleSynthesize);
        adapter.registerControlCapability(this::handleControl);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        if (!config.isTtsEnabled()) {
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS is disabled");
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS is disabled");
            return;
        }
        DefaultTtsSynthesisEngine synthesisEngine = new DefaultTtsSynthesisEngine(env, config, modelService);
        ttsRuntime = new TtsRuntime(
                env,
                runtime,
                synthesisEngine,
                audioBridge,
                ignored -> { },
                this::publishPlaybackStatus,
                adapter::publishRequestStatus
        );
        if (moduleService != null) {
            moduleService.bindRuntime(ttsRuntime);
        }
        context.services().register(TtsRuntime.class, ttsRuntime);
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS model is not loaded");
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS model is not loaded");
        publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), "tianshu.presence.module.tts.loading"));
    }

    private void completePreparation(ModuleRuntimeContext context, boolean initialized) {
        if (initialized) {
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.SYNTHESIS, moduleId());
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
            publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.tts.ready"));
        } else {
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS synthesis engine initialization failed");
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.tts.failed"));
        }
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (ttsRuntime != null) {
            ttsRuntime.start();
            scheduleAutoLoad(context, ttsRuntime);
        }
    }

    private void scheduleAutoLoad(ModuleRuntimeContext context, TtsRuntime expectedRuntime) {
        long generation = lifecycleGeneration.incrementAndGet();
        delayedAutoLoad = runtime.schedule(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.SCHEDULED)
                        .concurrencyKey("module.tts:auto-load")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .interruptible(true)
                        .build(),
                () -> {
                    if (destroyed || generation != lifecycleGeneration.get() || ttsRuntime != expectedRuntime) {
                        return;
                    }
                    TtsOperationResult preparation = expectedRuntime.prepare(
                            voiceProfile(TtsVoiceOptions.defaults()),
                            initialized -> {
                                if (!destroyed && generation == lifecycleGeneration.get() && ttsRuntime == expectedRuntime) {
                                    completePreparation(context, initialized);
                                }
                            }
                    );
                    if (!preparation.accepted()) {
                        completePreparation(context, false);
                    }
                },
                Duration.ofMillis(Math.max(0L, config.getTtsAutoLoadDelayMillis()))
        );
        if (delayedAutoLoad.state() == ProtocolTaskState.REJECTED) {
            completePreparation(context, false);
        }
    }

    @Override
    public void stop() {
        lifecycleGeneration.incrementAndGet();
        ProtocolTaskHandle scheduled = delayedAutoLoad;
        delayedAutoLoad = null;
        if (scheduled != null && !scheduled.isDone()) {
            scheduled.cancel("TTS module stopped");
        }
        if (ttsRuntime != null) {
            ttsRuntime.stop();
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        if (ttsRuntime != null) {
            if (moduleService != null) {
                moduleService.unbindRuntime(ttsRuntime);
                moduleService.unbindModelService(modelService);
            }
            ttsRuntime.destroy();
            ttsRuntime = null;
        }
    }

    private void handleSpeak(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSpeakPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
            return;
        }
        java.util.Optional<TtsFailure> voiceFailure = validateVoice(payload.voice());
        if (voiceFailure.isPresent()) {
            failProtocol(context, envelope.envelopeId(), "TTS_VOICE_UNAVAILABLE", voiceFailure.get());
            return;
        }
        PacketType packetType = envelope.header().packetType();
        boolean streaming = packetType == PacketType.STREAM_CHUNK || packetType == PacketType.STREAM_END;
        if (streaming && payload.inputMode() == com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode.DOCUMENT) {
            context.fail(envelope.envelopeId(), "INVALID_TTS_INPUT_MODE", "TTS stream packets require a stream input mode", null);
            return;
        }
        if (!streaming && payload.inputMode() != com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode.DOCUMENT) {
            context.fail(envelope.envelopeId(), "INVALID_TTS_INPUT_MODE", "TTS command packets require DOCUMENT input mode", null);
            return;
        }
        TtsSpeechSessionKey key = speechSessionKey(envelope, payload);
        TtsRequest request = requestFromPayload(envelope, payload, key, playbackPolicy(payload), priority(envelope));
        TtsOperationResult result = ttsRuntime.submitSpeech(
                key,
                payload.inputMode(),
                !streaming || packetType == PacketType.STREAM_END,
                request
        );
        if (result.accepted()) {
            context.complete(envelope.envelopeId());
        } else {
            failProtocol(context, envelope.envelopeId(), "TTS_FAILED", result.failure());
        }
    }

    private void handleSynthesize(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSynthesisRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS synthesis payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
            return;
        }
        java.util.Optional<TtsFailure> voiceFailure = validateVoice(payload.voice());
        if (voiceFailure.isPresent()) {
            failProtocol(context, envelope.envelopeId(), "TTS_VOICE_UNAVAILABLE", voiceFailure.get());
            return;
        }
        TtsRequest request = synthesisRequestFromPayload(envelope, payload);
        ttsRuntime.synthesize(
                request,
                payload.streaming(),
                payload.ttlMillis(),
                (chunkIndex, audio, last) -> adapter.respondAudio(envelope, new TtsAudioPayload(
                        request.requestId(),
                        audio,
                        ttsRuntime.sampleRate(),
                        1,
                        chunkIndex,
                        last
                )),
                () -> context.complete(envelope.envelopeId()),
                failure -> failProtocol(context, envelope.envelopeId(), "TTS_SYNTHESIS_FAILED", failure)
        );
    }

    private void handleControl(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsControlPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS control payload is invalid", null);
            return;
        }
        if (payload.action() == TtsControlPayload.Action.STOP_CURRENT) {
            String reason = payload.reason().isBlank() ? "protocol current stop" : payload.reason();
            TtsControlResult result = ttsRuntime == null
                    ? moduleService.stopCurrent(reason)
                    : ttsRuntime.stopCurrent(reason);
            completeOrFailControl(context, envelope.envelopeId(), result);
            return;
        }
        if (payload.action() == TtsControlPayload.Action.STOP) {
            String reason = payload.reason().isBlank() ? "protocol control stop" : payload.reason();
            TtsControlResult result;
            if (ttsRuntime == null) {
                result = moduleService.stopAll(reason);
            } else {
                result = payload.targetRequestId().isBlank()
                        ? ttsRuntime.stopAll(reason)
                        : ttsRuntime.stopRequest(payload.targetRequestId(), reason);
            }
            completeOrFailControl(context, envelope.envelopeId(), result);
            return;
        }
        if (payload.action() == TtsControlPayload.Action.STOP_SOURCE) {
            String reason = payload.reason().isBlank() ? "protocol source stop" : payload.reason();
            TtsControlResult result = ttsRuntime == null
                    ? runtimeUnavailable(TtsControlAction.STOP_SOURCE)
                    : ttsRuntime.stopSource(resolveSource(payload.targetSource()), reason);
            completeOrFailControl(context, envelope.envelopeId(), result);
            return;
        }
        if (payload.action() == TtsControlPayload.Action.RELOAD_MODEL) {
            publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), "tianshu.presence.module.tts.reload_started"));
            if (ttsRuntime == null) {
                completeOrFailControl(context, envelope.envelopeId(), moduleService.reloadModel());
                return;
            }
            TtsOperationResult submitted = ttsRuntime.reloadModel(result -> {
                if (result != null && result.accepted()) {
                    publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.tts.reload_complete"));
                } else {
                    publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.tts.reload_failed"));
                }
                completeOrFailControl(context, envelope.envelopeId(), result);
            });
            if (!submitted.accepted()) {
                TtsControlResult rejected = TtsControlResult.rejected(TtsControlAction.RELOAD_MODEL, submitted.failure());
                completeOrFailControl(context, envelope.envelopeId(), rejected);
            }
            return;
        }
        if (payload.action() == TtsControlPayload.Action.LOAD_VOICE) {
            completeOrFailControl(context, envelope.envelopeId(), voiceRegistry().load(
                    payload.voiceId(),
                    envelope.header().sourceId(),
                    payload.voiceSample(),
                    payload.referenceText()
            ));
            return;
        }
        if (payload.action() == TtsControlPayload.Action.IMPORT_VOICE) {
            completeOrFailControl(context, envelope.envelopeId(), voiceRegistry().importVoice(
                    payload.voiceId(),
                    envelope.header().sourceId(),
                    payload.voiceAudio(),
                    payload.referenceText()
            ));
            return;
        }
        if (payload.action() == TtsControlPayload.Action.UNLOAD_VOICE) {
            completeOrFailControl(context, envelope.envelopeId(), voiceRegistry().unload(
                    payload.voiceId(),
                    envelope.header().sourceId()
            ));
            return;
        }
        if (payload.action() == TtsControlPayload.Action.CLEAR_VOICE_CACHE) {
            completeOrFailControl(context, envelope.envelopeId(), voiceRegistry().clear(envelope.header().sourceId()));
            return;
        }
        context.fail(envelope.envelopeId(), "UNSUPPORTED_TTS_CONTROL", payload.action().name(), null);
    }

    private TtsControlResult runtimeUnavailable(TtsControlAction action) {
        return TtsControlResult.rejected(action, TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not available"));
    }

    private TtsRequestSource resolveSource(String value) {
        return TtsRequestSource.from(value);
    }

    private boolean ensureRuntimeAvailable(ProtocolContext context, String envelopeId) {
        if (!config.isTtsEnabled()) {
            context.fail(envelopeId, "TTS_DISABLED", "TTS is disabled", null);
            return false;
        }
        if (ttsRuntime == null) {
            context.fail(envelopeId, "TTS_RUNTIME_NOT_RUNNING", "TTS runtime is not available", null);
            return false;
        }
        return true;
    }

    private TtsRequest requestFromPayload(
            TianshuEnvelope envelope,
            TtsSpeakPayload payload,
            TtsSpeechSessionKey key,
            TtsPlaybackPolicy policy,
            Priority priority
    ) {
        String requestId = payload.sessionId() > 0L ? groupIdFromPayload(payload) : key.value();
        return new TtsRequest(
                requestId,
                requestId,
                envelope.envelopeId(),
                envelope.traceId(),
                payload.text(),
                TtsRequestSource.of(envelope.header().sourceId()),
                policy,
                priority,
                voiceProfile(payload.voice())
        );
    }

    private TtsRequest synthesisRequestFromPayload(TianshuEnvelope envelope, TtsSynthesisRequestPayload payload) {
        String requestId = payload.requestId().isBlank() ? envelope.envelopeId() : payload.requestId();
        return new TtsRequest(
                requestId,
                requestId,
                envelope.envelopeId(),
                envelope.traceId(),
                payload.text(),
                TtsRequestSource.of(envelope.header().sourceId()),
                TtsPlaybackPolicy.QUEUE,
                envelope.header().priority(),
                voiceProfile(payload.voice())
        );
    }

    private TtsSpeechSessionKey speechSessionKey(TianshuEnvelope envelope, TtsSpeakPayload payload) {
        String localId = envelope.traceId() == null || envelope.traceId().isBlank()
                ? envelope.envelopeId()
                : envelope.traceId();
        return TtsSpeechSessionKey.of(
                envelope.header().sourceId(),
                payload.sessionId(),
                payload.turnId(),
                localId
        );
    }

    private String groupIdFromPayload(TtsSpeakPayload payload) {
        return payload.sessionId() > 0 ? "speak:" + payload.sessionId() + ":" + payload.turnId() : "";
    }

    private TtsVoiceProfile voiceProfile(TtsVoiceOptions voiceOptions) {
        TtsVoiceOptions options = voiceOptions == null ? TtsVoiceOptions.defaults() : voiceOptions;
        String voiceId = options.voiceId();
        if (modelService == null) {
            return new TtsVoiceProfile(voiceId, options.speed() == null ? 1.0F : options.speed(),
                    options.speakerId() == null ? 0 : options.speakerId(), "");
        }
        com.rheinmetal.tianshu.model.TtsModelInfo info = modelService.resolveCurrentModelInfo();
        com.rheinmetal.tianshu.model.ModelSettings.TtsSettings settings = modelService.loadSettings(info);
        float speed = options.speed() == null ? (float) settings.speed : options.speed();
        int speakerId = options.speakerId() == null ? settings.speakerId : options.speakerId();
        String voiceSample = "";
        if (!voiceId.isBlank() && info != null && info.supportsVoiceClone()) {
            java.util.Optional<TtsVoiceCloneProfile> profile = voiceRegistry().resolve(voiceId);
            if (profile.isPresent()) {
                TtsVoiceCloneProfile clone = profile.get();
                return new TtsVoiceProfile(
                        clone.voiceId(),
                        speed,
                        speakerId,
                        clone.samplePath().toString(),
                        clone.referenceAudio().samples(),
                        clone.referenceAudio().sampleRate(),
                        clone.referenceText()
                );
            }
        }
        if (info != null && info.supportsVoiceClone()) {
            java.nio.file.Path resolved = null;
            if (settings.selectedVoiceSample != null && !settings.selectedVoiceSample.isBlank() && voiceLibraryService != null) {
                resolved = voiceLibraryService.resolveVoiceSamplePath(settings.selectedVoiceSample);
            }
            if (resolved == null && modelService != null) {
                resolved = modelService.resolveVoiceSamplePath(info, settings.selectedVoiceSample);
            }
            voiceSample = resolved == null ? "" : resolved.toString();
        }
        return new TtsVoiceProfile(voiceId, speed, speakerId, voiceSample);
    }

    private java.util.Optional<TtsFailure> validateVoice(TtsVoiceOptions voiceOptions) {
        com.rheinmetal.tianshu.model.TtsModelInfo info = modelService == null
                ? null
                : modelService.resolveCurrentModelInfo();
        return TtsVoiceRequestValidator.validate(
                voiceOptions,
                info != null && info.supportsVoiceClone(),
                voiceId -> voiceRegistry().resolve(voiceId).isPresent()
        );
    }

    private TtsVoiceCloneRegistry voiceRegistry() {
        if (voiceCloneRegistry == null) {
            voiceCloneRegistry = new TtsVoiceCloneRegistry(env, config);
        }
        return voiceCloneRegistry;
    }

    private TtsPlaybackPolicy playbackPolicy(TtsSpeakPayload payload) {
        return switch (payload.placement()) {
            case DROP_IF_BUSY -> TtsPlaybackPolicy.DROP_IF_BUSY;
            case QUEUE_AFTER_SESSION -> TtsPlaybackPolicy.QUEUE;
            case INSERT_AFTER_SESSION -> TtsPlaybackPolicy.INSERT_AFTER_SESSION;
            case INSERT_AFTER_SENTENCE -> TtsPlaybackPolicy.INSERT_AFTER_SENTENCE;
            case CANCEL_SENTENCE_AND_PLAY -> TtsPlaybackPolicy.CANCEL_SENTENCE_AND_PLAY;
            case CANCEL_SESSION_AND_PLAY -> TtsPlaybackPolicy.CANCEL_SESSION_AND_PLAY;
        };
    }

    private Priority priority(TianshuEnvelope envelope) {
        return envelope.header().priority() == null ? Priority.LOW : envelope.header().priority();
    }

    private void completeOrFailControl(ProtocolContext context, String envelopeId, TtsControlResult result) {
        if (result != null && result.accepted()) {
            context.complete(envelopeId);
            return;
        }
        TtsFailure failure = result == null ? null : result.failure();
        failProtocol(context, envelopeId, "TTS_CONTROL_FAILED", failure);
    }

    private void failProtocol(ProtocolContext context, String envelopeId, String fallbackCode, TtsFailure failure) {
        String code = failure == null ? fallbackCode : "TTS_" + failure.code().name();
        String message = failure == null ? "" : failure.message();
        context.fail(envelopeId, code, message, failure == null ? null : failure.cause());
    }

    private void publishPlaybackStatus(TtsPlaybackState state) {
        adapter.publishPlaybackStatus(TtsPlaybackStatusPayload.now(state));
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }

}


