package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.echo.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.echo.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsProtocolCodecExecution;
import com.rheinmetal.tianshu.function.echo.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRuntime;
import com.rheinmetal.tianshu.function.echo.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.echo.runtime.TtsSpeechSessionKey;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.echo.synthesis.DefaultTtsSynthesisEngine;
import com.rheinmetal.tianshu.function.echo.voice.TtsVoiceCloneProfile;
import com.rheinmetal.tianshu.function.echo.voice.TtsVoiceCloneRegistry;
import com.rheinmetal.tianshu.function.echo.voice.TtsVoiceRequestValidator;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsAudioAckPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsControlPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsPlaybackState;
import com.rheinmetal.tianshu.function.echo.contract.TtsSpeakPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.function.echo.contract.TtsVoiceOptions;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TtsModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess runtime;
    private final IGameEnvironment env;
    private final EchoConfiguration config;
    private final TtsProtocolAdapter adapter;
    private TtsRuntime ttsRuntime;
    private TtsModuleService moduleService;
    private TtsModelService modelService;
    private TtsVoiceLibraryService voiceLibraryService;
    private TtsVoiceCloneRegistry voiceCloneRegistry;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile ProtocolTaskHandle delayedAutoLoad;
    private volatile long lifecycleStatusGeneration;
    private volatile boolean lifecycleActive;
    private volatile boolean destroyed;
    /** Capability registry of the running session; null before prepare and after destroy. */
    private volatile ModuleRuntimeState runtimeState;
    private final Set<String> activeRequestActivities = ConcurrentHashMap.newKeySet();
    /** Process-wide serial gate for native model loads; null keeps this module self-contained. */
    private final NativeModelLoadQueue loadQueue;
    /** Tells the player the speech model is queued behind retrieval instead of stalled. */
    private final NativeModelLoadQueue.Observer loadQueueObserver;

    public TtsModule(IAudioBridge audioBridge, ModuleRuntimeAccess runtime, IGameEnvironment env, EchoConfiguration config) {
        this(audioBridge, runtime, env, config, null);
    }

    public TtsModule(IAudioBridge audioBridge, ModuleRuntimeAccess runtime, IGameEnvironment env, EchoConfiguration config,
                     NativeModelLoadQueue loadQueue) {
        this.audioBridge = audioBridge;
        this.runtime = runtime;
        this.env = env;
        this.config = config;
        this.adapter = new TtsProtocolAdapter(runtime);
        this.loadQueue = loadQueue;
        this.loadQueueObserver = new NativeModelLoadQueue.Observer() {
            @Override
            public void onParked(String moduleId, String action, int ahead) {
                if (!TtsModuleId.VALUE.equals(moduleId)) {
                    return;
                }
                publishModuleStatus(ModuleStatuses.waitingKeyed(
                        TtsModuleId.VALUE, "tianshu.runtime.module.tts.loading"));
            }

            @Override
            public void onGranted(String moduleId, String action) {
                // The load publishes its own progress once it runs; nothing to add here.
            }
        };
        if (this.loadQueue != null) {
            this.loadQueue.addObserver(this.loadQueueObserver);
        }
    }

    @Override
    public String moduleId() {
        return TtsModuleId.VALUE;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        if (loadQueue != null) {
            // Announce the tier at registration, which runs for every installed module before any module is
            // prepared: the speech-input load must be able to wait for a speech-output load that is still to
            // come even when the input module prepares first. A kernel without this module never sees the
            // expectation, so its input load is not parked forever.
            loadQueue.expect(NativeModelLoadQueue.Tier.DEFERRED);
        }
        adapter.registerOwnedTopics(context.protocol());
        modelService = new TtsModelService(env, config, runtime, this::publishModuleStatus);
        moduleService = new TtsModuleService();
        moduleService.bindModelService(modelService);
        voiceLibraryService = new TtsVoiceLibraryService(env, config, runtime);
        voiceCloneRegistry = new TtsVoiceCloneRegistry(env, config);
        context.services().register(TtsModuleService.class, moduleService);
        context.services().register(TtsModelService.class, modelService);
        context.services().register(TtsVoiceLibraryService.class, voiceLibraryService);
        context.services().register(TtsVoiceCloneRegistry.class, voiceCloneRegistry);
        adapter.registerSpeakCapability(guarded("speak", this::handleSpeak));
        adapter.registerSynthesizeCapability(guarded("synthesize", this::handleSynthesize));
        adapter.registerAudioAckCapability(guarded("audio-ack", this::handleAudioAck));
        adapter.registerControlCapability(guarded("control", this::handleControl));
    }

    /** What a capability handler does; declared so {@link #guarded} can wrap all four the same way. */
    @FunctionalInterface
    private interface CapabilityHandler {
        void handle(TianshuEnvelope envelope, ProtocolContext context) throws Exception;
    }

    /**
     * Makes every envelope end, whatever the handler does.
     *
     * <p>An exception that escapes a handler leaves the caller's request without a terminal state: the
     * protocol converts the {@code Exception}s it sees, but an {@code Error} travels past it and the envelope
     * simply stops existing. Failing it here means the caller always learns the outcome.</p>
     */
    private com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler guarded(String action, CapabilityHandler body) {
        return (envelope, context) -> {
            try {
                body.handle(envelope, context);
            } catch (Throwable failure) {
                failProtocol(context, envelope.envelopeId(),
                        com.rheinmetal.tianshu.function.echo.runtime.TtsRuntimeFailurePolicy.classify(
                                TtsFailureCode.UNKNOWN, failure));
                if (env != null) {
                    env.error("tts.handler.failed: " + action, failure);
                }
            }
        };
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        lifecycleStatusGeneration++;
        lifecycleActive = true;
        runtimeState = context.runtimeState();
        if (loadQueue != null) {
            // Re-arms the ordering claim for this session; register() announces it once so the speech-input
            // load cannot overtake it, and a stopped-and-restarted module has to say so again.
            loadQueue.expect(NativeModelLoadQueue.Tier.DEFERRED);
        }
        publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleStatusGeneration));
        if (!config.isTtsEnabled()) {
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS is disabled");
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS is disabled");
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.VOICE_LIBRARY, moduleId(), "TTS is disabled");
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.MODEL_MANAGEMENT, moduleId(), "TTS is disabled");
            if (loadQueue != null) {
                // Disabled means no speech load will ever come from this tier; the voice-input load parked
                // behind it must not wait for one.
                loadQueue.abandon(NativeModelLoadQueue.Tier.DEFERRED);
            }
            publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), "tianshu.runtime.module.tts.failed", lifecycleStatusGeneration));
            return;
        }
        DefaultTtsSynthesisEngine synthesisEngine = new DefaultTtsSynthesisEngine(
                env,
                config,
                modelService,
                new TtsProtocolCodecExecution(runtime)
        );
        ttsRuntime = new TtsRuntime(
                env,
                runtime,
                synthesisEngine,
                audioBridge,
                ignored -> { },
                this::publishPlaybackStatus,
                this::publishRequestStatus,
                adapter::publishLoadingActivity,
                config::isTtsStreamingSynthesisEnabled,
                loadQueue,
                this::markModelBusy
        );
        if (moduleService != null) {
            moduleService.bindRuntime(ttsRuntime);
        }
        context.services().register(TtsRuntime.class, ttsRuntime);
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS model is not loaded");
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS model is not loaded");
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.VOICE_LIBRARY, moduleId(), "TTS model is not loaded");
        context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.MODEL_MANAGEMENT, moduleId(), "TTS model is not loaded");
        publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), "tianshu.runtime.module.tts.loading"));
    }

    private void completePreparation(ModuleRuntimeContext context, boolean initialized) {
        if (lifecycleStatusGeneration <= 0L) {
            return;
        }
        if (initialized) {
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.SYNTHESIS, moduleId());
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.VOICE_LIBRARY, moduleId());
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.MODEL_MANAGEMENT, moduleId());
            publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.runtime.module.tts.ready"));
            publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), lifecycleStatusGeneration));
        } else {
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS synthesis engine initialization failed");
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.VOICE_LIBRARY, moduleId(), "TTS synthesis engine initialization failed");
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.MODEL_MANAGEMENT, moduleId());
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.runtime.module.tts.failed"));
            publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), "tianshu.runtime.module.tts.failed", lifecycleStatusGeneration));
        }
    }

    /**
     * The capability bits have to describe what a caller can do now, not only whether the module started.
     * A model operation (preview, reload) rejects outside synthesis for its duration, and a stopped module
     * accepts nothing, so both states are published rather than left behind as a misleading ready bit.
     */
    private void markModelBusy(boolean busy) {
        ModuleRuntimeState state = runtimeState;
        if (state == null) {
            return;
        }
        if (busy) {
            state.capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS model operation in progress");
            state.capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS model operation in progress");
            return;
        }
        // A late completion from a preview or reload that outlived the module must not resurrect the
        // capability bits of a stopped module.
        if (!lifecycleActive) {
            markStopped();
            return;
        }
        if (ttsRuntime != null && ttsRuntime.isReady()) {
            state.capabilities().markReady(TtsRuntimeCapabilities.SYNTHESIS, moduleId());
            state.capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
        } else {
            state.capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS model is not loaded");
            state.capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS model is not loaded");
        }
    }

    private void markStopped() {
        ModuleRuntimeState state = runtimeState;
        if (state == null) {
            return;
        }
        state.capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS module is stopped");
        state.capabilities().markFailed(TtsRuntimeCapabilities.PLAYBACK, moduleId(), "TTS module is stopped");
        state.capabilities().markFailed(TtsRuntimeCapabilities.VOICE_LIBRARY, moduleId(), "TTS module is stopped");
        state.capabilities().markFailed(TtsRuntimeCapabilities.MODEL_MANAGEMENT, moduleId(), "TTS module is stopped");
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
                            voiceProfile(TtsVoiceOptions.defaults(), ""),
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
        if (loadQueue != null) {
            // The scheduled auto-load may have been cancelled above, so its tier is given up here rather
            // than in the load that will never run — otherwise the voice-input tier would wait forever.
            // A load that is already running is not affected: it hands the permit back when it returns, and
            // clearing it here would let the next session read a model while this one is still reading.
            loadQueue.abandon(NativeModelLoadQueue.Tier.DEFERRED);
        }
        if (ttsRuntime != null) {
            ttsRuntime.stop();
        }
        if (modelService != null) {
            modelService.stop();
        }
        markStopped();
        endActiveRequestActivities();
        if (lifecycleActive) {
            lifecycleActive = false;
            publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        if (loadQueue != null) {
            loadQueue.removeObserver(loadQueueObserver);
        }
        if (modelService != null) {
            modelService.stop();
        }
        if (ttsRuntime != null) {
            if (moduleService != null) {
                moduleService.unbindRuntime(ttsRuntime);
                moduleService.unbindModelService(modelService);
            }
            ttsRuntime.destroy();
            ttsRuntime = null;
        }
        runtimeState = null;
        endActiveRequestActivities();
    }

    private void handleSpeak(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSpeakPayload payload)) {
            failWith(context, envelope.envelopeId(), "TTS_INVALID_PAYLOAD", "TTS payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
            return;
        }
        java.util.Optional<TtsFailure> voiceFailure = validateVoice(payload.voice());
        if (voiceFailure.isPresent()) {
            failProtocol(context, envelope.envelopeId(), voiceFailure.get());
            return;
        }
        PacketType packetType = envelope.header().packetType();
        boolean streaming = packetType == PacketType.STREAM_CHUNK || packetType == PacketType.STREAM_END;
        if (streaming && payload.inputMode() == com.rheinmetal.tianshu.function.echo.contract.TtsTextInputMode.DOCUMENT) {
            failWith(context, envelope.envelopeId(), failureReasonCode(TtsFailureCode.INVALID_REQUEST),
                    "TTS stream packets require a stream input mode", null);
            return;
        }
        if (!streaming && payload.inputMode() != com.rheinmetal.tianshu.function.echo.contract.TtsTextInputMode.DOCUMENT) {
            failWith(context, envelope.envelopeId(), failureReasonCode(TtsFailureCode.INVALID_REQUEST),
                    "TTS command packets require DOCUMENT input mode", null);
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
            failProtocol(context, envelope.envelopeId(), result.failure());
        }
    }

    private void handleSynthesize(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSynthesisRequestPayload payload)) {
            failWith(context, envelope.envelopeId(), "TTS_INVALID_PAYLOAD", "TTS synthesis payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
            return;
        }
        java.util.Optional<TtsFailure> voiceFailure = validateVoice(payload.voice());
        if (voiceFailure.isPresent()) {
            failProtocol(context, envelope.envelopeId(), voiceFailure.get());
            return;
        }
        TtsRequest request = synthesisRequestFromPayload(envelope, payload);
        String sourceId = envelope.header().sourceId();
        ttsRuntime.synthesizeAcknowledged(
                request,
                payload.ttlMillis(),
                sourceId,
                audio -> adapter.respondAudio(envelope, new TtsAudioPayload(
                        request.requestId(),
                        audio,
                        ttsRuntime.sampleRate(),
                        1
                )),
                () -> beginRequestActivity(sourceId, request.requestId()),
                () -> {
                    endRequestActivity(request.requestId());
                    context.complete(envelope.envelopeId());
                },
                failure -> {
                    endRequestActivity(request.requestId());
                    failProtocol(context, envelope.envelopeId(), failure);
                }
        );
    }

    private void handleAudioAck(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsAudioAckPayload payload)) {
            failWith(context, envelope.envelopeId(), "TTS_INVALID_PAYLOAD", "TTS audio acknowledgement payload is invalid", null);
            return;
        }
        if (ttsRuntime == null) {
            failWith(context, envelope.envelopeId(), "TTS_RUNTIME_NOT_RUNNING", "TTS runtime is not available", null);
            return;
        }
        TtsOperationResult result = ttsRuntime.acknowledgeSynthesisAudio(
                envelope.header().sourceId(),
                payload
        );
        if (result.accepted()) {
            context.complete(envelope.envelopeId());
        } else {
            failProtocol(context, envelope.envelopeId(), result.failure());
        }
    }

    private void handleControl(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsControlPayload payload)) {
            failWith(context, envelope.envelopeId(), "TTS_INVALID_PAYLOAD", "TTS control payload is invalid", null);
            return;
        }
        TtsControlResult result = switch (payload.action()) {
            case STOP_CURRENT -> {
                String reason = payload.reason().isBlank() ? "protocol current stop" : payload.reason();
                yield ttsRuntime == null
                        ? moduleService.stopCurrent(reason)
                        : ttsRuntime.stopCurrent(reason);
            }
            case STOP -> {
                String reason = payload.reason().isBlank() ? "protocol control stop" : payload.reason();
                if (ttsRuntime == null) {
                    yield moduleService.stopAll(reason);
                }
                yield payload.targetRequestId().isBlank()
                        ? ttsRuntime.stopAll(reason)
                        : ttsRuntime.stopRequest(payload.targetRequestId(), reason);
            }
            case STOP_SOURCE -> {
                String reason = payload.reason().isBlank() ? "protocol source stop" : payload.reason();
                yield ttsRuntime == null
                        ? runtimeUnavailable(TtsControlAction.STOP_SOURCE)
                        : ttsRuntime.stopSource(TtsRequestSource.from(payload.targetSource()), reason);
            }
            case RELOAD_MODEL -> requestModelReload(context, envelope.envelopeId());
            case LOAD_VOICE -> voiceRegistry().load(
                    payload.voiceId(),
                    envelope.header().sourceId(),
                    payload.voiceSample(),
                    payload.referenceText()
            );
            case IMPORT_VOICE -> voiceRegistry().importVoice(
                    payload.voiceId(),
                    envelope.header().sourceId(),
                    payload.voiceAudio(),
                    payload.referenceText()
            );
            case UNLOAD_VOICE -> voiceRegistry().unload(
                    payload.voiceId(),
                    envelope.header().sourceId()
            );
            case CLEAR_VOICE_CACHE -> voiceRegistry().clear(envelope.header().sourceId());
        };
        // The reload answers on the model lane; every other action answers here.
        if (result != null) {
            completeOrFailControl(context, envelope.envelopeId(), result);
        }
    }

    private TtsControlResult requestModelReload(ProtocolContext context, String envelopeId) {
        if (ttsRuntime == null) {
            return moduleService.reloadModel();
        }
        publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), "tianshu.runtime.module.tts.reload_started"));
        // The capability bits follow the model lane through markModelBusy, which the coordinator drives; a
        // reload is one more span of that lane, not a second source of truth about it.
        ttsRuntime.reloadModel(result -> {
            publishModuleStatus(result != null && result.accepted()
                    ? ModuleStatuses.readyKeyed(moduleId(), "tianshu.runtime.module.tts.reload_complete")
                    : ModuleStatuses.failedKeyed(moduleId(), "tianshu.runtime.module.tts.reload_failed"));
            completeOrFailControl(context, envelopeId, result);
        });
        return null;
    }

    private TtsControlResult runtimeUnavailable(TtsControlAction action) {
        return TtsControlResult.rejected(action, TtsFailure.of(TtsFailureCode.RUNTIME_NOT_RUNNING, "TTS runtime is not available"));
    }

    private TtsRequestSource resolveSource(String value) {
        return TtsRequestSource.from(value);
    }

    private boolean ensureRuntimeAvailable(ProtocolContext context, String envelopeId) {
        if (!config.isTtsEnabled()) {
            failWith(context, envelopeId, "TTS_DISABLED", "TTS is disabled", null);
            return false;
        }
        if (ttsRuntime == null) {
            failWith(context, envelopeId, "TTS_RUNTIME_NOT_RUNNING", "TTS runtime is not available", null);
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
                voiceProfile(payload.voice(), payload.text())
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
                voiceProfile(payload.voice(), payload.text())
        );
    }

    private TtsSpeechSessionKey speechSessionKey(TianshuEnvelope envelope, TtsSpeakPayload payload) {
        // A caller that declares a session id owns the stream identity through it. Without one, the
        // envelope is the identity: a trace groups several envelopes, so using it here merged two
        // independent documents that happened to share a parent.
        return TtsSpeechSessionKey.of(
                envelope.header().sourceId(),
                payload.sessionId(),
                payload.turnId(),
                envelope.envelopeId()
        );
    }

    private String groupIdFromPayload(TtsSpeakPayload payload) {
        return payload.sessionId() > 0 ? "speak:" + payload.sessionId() + ":" + payload.turnId() : "";
    }

    private TtsVoiceProfile voiceProfile(TtsVoiceOptions voiceOptions, String text) {
        TtsVoiceOptions options = voiceOptions == null ? TtsVoiceOptions.defaults() : voiceOptions;
        String voiceId = options.voiceId();
        if (modelService == null) {
            return new TtsVoiceProfile(voiceId, options.speed() == null ? 1.0F : options.speed(),
                    options.speakerId() == null ? 0 : options.speakerId(), "");
        }
        com.rheinmetal.tianshu.function.echo.model.TtsModelInfo info = modelService.resolveCurrentModelInfo();
        com.rheinmetal.tianshu.function.echo.model.TtsModelSettings settings = modelService.loadSettings(info);
        float speed = options.speed() == null ? (float) settings.speed : options.speed();
        // An explicit override beats the model's own choice of voice for the text at hand.
        int speakerId = options.speakerId() != null
                ? options.speakerId()
                : com.rheinmetal.tianshu.function.echo.model.TtsSpeakerSelection.select(info, text, settings.speakerId);
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
        com.rheinmetal.tianshu.function.echo.model.TtsModelInfo info = modelService == null
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
        failProtocol(context, envelopeId, result == null ? null : result.failure());
    }

    /** One failure vocabulary: every structured reason code is {@code TTS_} plus a {@link TtsFailureCode}. */
    private void failProtocol(ProtocolContext context, String envelopeId, TtsFailure failure) {
        TtsFailure resolved = failure == null ? TtsFailure.of(TtsFailureCode.UNKNOWN, "") : failure;
        failWith(context, envelopeId, failureReasonCode(resolved.code()), resolved.message(), resolved.cause());
    }

    private void failWith(ProtocolContext context, String envelopeId, String reasonCode, String message, Throwable throwable) {
        context.fail(envelopeId, reasonCode, message == null ? "" : message, throwable);
    }

    private static String failureReasonCode(TtsFailureCode code) {
        return "TTS_" + (code == null ? TtsFailureCode.UNKNOWN.name() : code.name());
    }

    private void publishPlaybackStatus(TtsPlaybackState state) {
        adapter.publishPlaybackStatus(TtsPlaybackStatusPayload.now(state));
    }

    void publishRequestStatus(com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatusPayload status) {
        if (status == null) {
            return;
        }
        adapter.publishRequestStatus(status);
        if ("module.ax".equals(status.sourceId()) || status.requestId().isBlank()) {
            return;
        }
        if (status.status() == com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatus.QUEUED
                || status.status() == com.rheinmetal.tianshu.function.echo.contract.TtsRequestStatus.PLAYING) {
            beginRequestActivity(status.sourceId(), status.requestId());
            return;
        }
        endRequestActivity(status.requestId());
    }

    private void beginRequestActivity(String sourceId, String requestId) {
        if ("module.ax".equals(sourceId) || requestId == null || requestId.isBlank()) {
            return;
        }
        String normalizedRequestId = requestId.trim();
        if (activeRequestActivities.add(normalizedRequestId)) {
            adapter.publishRequestActivity(normalizedRequestId, true);
        }
    }

    private void endRequestActivity(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        String normalizedRequestId = requestId.trim();
        if (activeRequestActivities.remove(normalizedRequestId)) {
            adapter.publishRequestActivity(normalizedRequestId, false);
        }
    }

    private void endActiveRequestActivities() {
        for (String requestId : java.util.List.copyOf(activeRequestActivities)) {
            if (activeRequestActivities.remove(requestId)) {
                adapter.publishRequestActivity(requestId, false);
            }
        }
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }

}


