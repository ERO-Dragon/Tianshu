package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.event.TtsPlaybackEndEvent;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.tts.runtime.TtsPlaybackPolicy;
import com.rheinmetal.tianshu.function.tts.runtime.TtsPlaybackStatusMapper;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntime;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.tts.synthesis.DefaultTtsSynthesisEngine;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.CancelPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class TtsModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final TianshuEventBus eventBus;
    private final ProtocolRuntime runtime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final TtsProtocolAdapter adapter;
    private TtsRuntime ttsRuntime;
    private TtsModuleService moduleService;
    private TtsModelService modelService;
    private VoiceNotificationService voiceNotificationService;
    private TtsVoiceLibraryService voiceLibraryService;

    public TtsModule(IAudioBridge audioBridge, TianshuEventBus eventBus, ProtocolRuntime runtime, IGameEnvironment env, ITianshuConfig config) {
        this.audioBridge = audioBridge;
        this.eventBus = eventBus;
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
        modelService = new TtsModelService(env, config, runtime.executors());
        moduleService = new TtsModuleService();
        moduleService.bindModelService(modelService);
        voiceNotificationService = new VoiceNotificationService(runtime);
        voiceLibraryService = new TtsVoiceLibraryService(env, config);
        context.services().register(TtsModuleService.class, moduleService);
        context.services().register(TtsModelService.class, modelService);
        context.services().register(VoiceNotificationService.class, voiceNotificationService);
        context.services().register(TtsVoiceLibraryService.class, voiceLibraryService);
        adapter.registerSpeakCapability(this::handleSpeak);
        adapter.registerAlertCapability(this::handleAlert);
        adapter.registerStopCapability(this::handleStop);
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
        ttsRuntime = new TtsRuntime(env, runtime.executors(), synthesisEngine, audioBridge, this::publishPlaybackStatus, this::publishPlaybackCompleted);
        if (moduleService != null) {
            moduleService.bindRuntime(ttsRuntime);
        }
        context.services().register(TtsRuntime.class, ttsRuntime);
        boolean initialized = ttsRuntime.prepare();
        if (initialized) {
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.SYNTHESIS, moduleId());
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
            env.executeOnMainThread(() -> env.displayMessageToPlayer("§b[天极] §f灵音共鸣已就绪"));
        } else {
            context.runtimeState().capabilities().markFailed(TtsRuntimeCapabilities.SYNTHESIS, moduleId(), "TTS synthesis engine initialization failed");
            context.runtimeState().capabilities().markReady(TtsRuntimeCapabilities.PLAYBACK, moduleId());
        }
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (ttsRuntime != null) {
            ttsRuntime.start();
        }
    }

    @Override
    public void stop() {
        if (ttsRuntime != null) {
            ttsRuntime.stop();
        }
    }

    @Override
    public void destroy() {
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
        TtsRequest request = requestFromPayload(envelope, payload, TtsRequestSource.ASSISTANT, payload.interruptCurrent() ? TtsPlaybackPolicy.REPLACE_CURRENT : TtsPlaybackPolicy.QUEUE, Priority.LOW, false);
        ttsRuntime.submit(request, () -> context.complete(envelope.envelopeId()), failure -> failProtocol(context, envelope.envelopeId(), "TTS_FAILED", failure));
    }

    private void handleAlert(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSpeakPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS alert payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
            return;
        }
        TtsRequest request = requestFromPayload(envelope, payload, TtsRequestSource.ALERT, payload.interruptCurrent() ? TtsPlaybackPolicy.REPLACE_CURRENT : TtsPlaybackPolicy.INTERRUPT_LOWER_PRIORITY, Priority.HIGH, true);
        ttsRuntime.submit(request, () -> context.complete(envelope.envelopeId()), failure -> failProtocol(context, envelope.envelopeId(), "TTS_ALERT_FAILED", failure));
    }

    private void handleStop(TianshuEnvelope envelope, ProtocolContext context) {
        String reason = "protocol stop";
        if (envelope.payload() instanceof CancelPayload payload && payload.message() != null && !payload.message().isBlank()) {
            reason = payload.message();
        }
        TtsControlResult result = ttsRuntime == null
                ? moduleService.stopAll(reason)
                : ttsRuntime.stopAll(reason);
        completeOrFailControl(context, envelope.envelopeId(), result);
    }

    private void handleControl(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsControlPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS control payload is invalid", null);
            return;
        }
        if (ttsRuntime == null) {
            completeOrFailControl(context, envelope.envelopeId(), moduleService.stopAll("runtime unavailable"));
            return;
        }
        if (payload.action() == TtsControlPayload.Action.STOP) {
            String reason = payload.reason().isBlank() ? "protocol control stop" : payload.reason();
            TtsControlResult result = payload.targetRequestId().isBlank()
                    ? ttsRuntime.stopAll(reason)
                    : ttsRuntime.stopRequest(payload.targetRequestId(), reason);
            completeOrFailControl(context, envelope.envelopeId(), result);
            return;
        }
        if (payload.action() == TtsControlPayload.Action.STOP_SOURCE) {
            String reason = payload.reason().isBlank() ? "protocol source stop" : payload.reason();
            completeOrFailControl(context, envelope.envelopeId(), ttsRuntime.stopSource(resolveSource(payload.targetSource()), reason));
            return;
        }
        if (payload.action() == TtsControlPayload.Action.RELOAD_MODEL) {
            completeOrFailControl(context, envelope.envelopeId(), ttsRuntime.reloadModel());
            return;
        }
        context.fail(envelope.envelopeId(), "UNSUPPORTED_TTS_CONTROL", payload.action().name(), null);
    }

    private TtsRequestSource resolveSource(String value) {
        if (value == null || value.isBlank()) {
            return TtsRequestSource.UNKNOWN;
        }
        try {
            return TtsRequestSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TtsRequestSource.UNKNOWN;
        }
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

    private TtsRequest requestFromPayload(TianshuEnvelope envelope, TtsSpeakPayload payload, TtsRequestSource source, TtsPlaybackPolicy policy, Priority priority, boolean expectPlaybackEnd) {
        String requestId = payload.sessionId() > 0 ? source.name().toLowerCase() + ":" + payload.sessionId() + ":" + payload.turnId() : envelope.envelopeId();
        return new TtsRequest(
                requestId,
                envelope.envelopeId(),
                envelope.traceId(),
                payload.text(),
                source,
                policy,
                priority,
                voiceProfile(payload.voiceStyle()),
                expectPlaybackEnd
        );
    }

    private TtsVoiceProfile voiceProfile(String voiceStyle) {
        if (modelService == null) {
            return new TtsVoiceProfile(voiceStyle, 1.0f, 0, "");
        }
        com.rheinmetal.tianshu.model.TtsModelInfo info = modelService.resolveCurrentModelInfo();
        com.rheinmetal.tianshu.model.ModelSettings.TtsSettings settings = modelService.loadSettings(info);
        String voiceSample = "";
        if (info != null && info.supportsVoiceClone() && settings.selectedVoiceSample != null && !settings.selectedVoiceSample.isBlank() && voiceLibraryService != null) {
            java.nio.file.Path resolved = voiceLibraryService.resolveVoiceSamplePath(settings.selectedVoiceSample);
            voiceSample = resolved == null ? "" : resolved.toString();
        }
        return new TtsVoiceProfile(voiceStyle, (float) settings.speed, settings.speakerId, voiceSample);
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
        context.fail(envelopeId, code, message, null);
    }

    private void publishPlaybackStatus(TtsSession session) {
        adapter.publishPlaybackStatus(new TtsPlaybackStatusPayload(
                session.request().requestId(),
                session.request().traceId(),
                session.request().source().name().toLowerCase(),
                TtsPlaybackStatusMapper.phaseOf(session),
                session.request().priority(),
                TtsPlaybackStatusMapper.publicMessage(session),
                System.currentTimeMillis()
        ));
    }

    private void publishPlaybackCompleted(TtsSession session) {
        if (session.request().expectPlaybackEndEvent()) {
            eventBus.publishEvent(new TtsPlaybackEndEvent(session.request().source().name().toLowerCase()));
        }
    }
}
