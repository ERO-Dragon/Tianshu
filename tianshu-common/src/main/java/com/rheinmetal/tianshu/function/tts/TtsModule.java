package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
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
import com.rheinmetal.tianshu.function.tts.runtime.TtsStreamChunk;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.tts.synthesis.DefaultTtsSynthesisEngine;
import com.rheinmetal.tianshu.function.tts.voice.TtsVoiceCloneProfile;
import com.rheinmetal.tianshu.function.tts.voice.TtsVoiceCloneRegistry;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.TtsAudioPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsControlPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackState;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSynthesisRequestPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class TtsModule implements TianshuManagedModule {
    private final IAudioBridge audioBridge;
    private final ProtocolRuntime runtime;
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final TtsProtocolAdapter adapter;
    private TtsRuntime ttsRuntime;
    private TtsModuleService moduleService;
    private TtsModelService modelService;
    private VoiceNotificationService voiceNotificationService;
    private TtsVoiceLibraryService voiceLibraryService;
    private TtsVoiceCloneRegistry voiceCloneRegistry;

    public TtsModule(IAudioBridge audioBridge, ProtocolRuntime runtime, IGameEnvironment env, ITianshuConfig config) {
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
        modelService = new TtsModelService(env, config, runtime.executors());
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
        ttsRuntime = new TtsRuntime(env, runtime.executors(), synthesisEngine, audioBridge, ignored -> {}, this::publishPlaybackStatus);
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
        if (envelope.header().packetType() == PacketType.STREAM_CHUNK || envelope.header().packetType() == PacketType.STREAM_END) {
            TtsStreamChunk chunk = streamChunkFromPayload(envelope, payload, playbackPolicy(payload));
            ttsRuntime.submitStream(chunk, () -> context.complete(envelope.envelopeId()), failure -> failProtocol(context, envelope.envelopeId(), "TTS_FAILED", failure));
            return;
        }
        TtsRequest request = requestFromPayload(envelope, payload, playbackPolicy(payload), priority(envelope));
        ttsRuntime.submit(request, () -> context.complete(envelope.envelopeId()), failure -> failProtocol(context, envelope.envelopeId(), "TTS_FAILED", failure));
    }

    private void handleSynthesize(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSynthesisRequestPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS synthesis payload is invalid", null);
            return;
        }
        if (!ensureRuntimeAvailable(context, envelope.envelopeId())) {
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
            TtsControlResult result = ttsRuntime == null
                    ? moduleService.reloadModel()
                    : ttsRuntime.reloadModel();
            completeOrFailControl(context, envelope.envelopeId(), result);
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

    private TtsRequest requestFromPayload(TianshuEnvelope envelope, TtsSpeakPayload payload, TtsPlaybackPolicy policy, Priority priority) {
        String requestId = requestIdFromPayload(envelope, payload);
        return new TtsRequest(
                requestId,
                groupIdFromPayload(payload),
                envelope.envelopeId(),
                envelope.traceId(),
                payload.text(),
                TtsRequestSource.AX,
                policy,
                priority,
                voiceProfile(payload.voiceStyle()),
                false
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
                TtsRequestSource.UNKNOWN,
                TtsPlaybackPolicy.QUEUE,
                envelope.header().priority(),
                voiceProfile(payload.voiceStyle()),
                false
        );
    }

    private TtsStreamChunk streamChunkFromPayload(TianshuEnvelope envelope, TtsSpeakPayload payload, TtsPlaybackPolicy policy) {
        return new TtsStreamChunk(
                streamIdFromPayload(envelope, payload),
                envelope.envelopeId(),
                envelope.traceId(),
                payload.text(),
                TtsRequestSource.AX,
                policy,
                voiceProfile(payload.voiceStyle()),
                envelope.header().packetType() == PacketType.STREAM_END
        );
    }

    private String requestIdFromPayload(TianshuEnvelope envelope, TtsSpeakPayload payload) {
        String groupId = groupIdFromPayload(payload);
        return groupId.isBlank() ? envelope.envelopeId() : groupId + ":" + envelope.envelopeId();
    }

    private String streamIdFromPayload(TianshuEnvelope envelope, TtsSpeakPayload payload) {
        String groupId = groupIdFromPayload(payload);
        if (!groupId.isBlank()) {
            return groupId;
        }
        return envelope.traceId() == null || envelope.traceId().isBlank() ? envelope.envelopeId() : envelope.traceId();
    }

    private String groupIdFromPayload(TtsSpeakPayload payload) {
        return payload.sessionId() > 0 ? "speak:" + payload.sessionId() + ":" + payload.turnId() : "";
    }

    private TtsVoiceProfile voiceProfile(String voiceStyle) {
        if (modelService == null) {
            return new TtsVoiceProfile(voiceStyle, 1.0f, 0, "");
        }
        com.rheinmetal.tianshu.model.TtsModelInfo info = modelService.resolveCurrentModelInfo();
        com.rheinmetal.tianshu.model.ModelSettings.TtsSettings settings = modelService.loadSettings(info);
        String voiceSample = "";
        String voiceId = normalizeVoiceId(voiceStyle);
        if (!voiceId.isBlank() && info != null && info.supportsVoiceClone()) {
            java.util.Optional<TtsVoiceCloneProfile> profile = voiceRegistry().resolve(voiceId);
            if (profile.isPresent()) {
                TtsVoiceCloneProfile clone = profile.get();
                return new TtsVoiceProfile(
                        voiceStyle,
                        clone.voiceId(),
                        (float) settings.speed,
                        settings.speakerId,
                        clone.samplePath().toString(),
                        clone.referenceAudio().samples(),
                        clone.referenceAudio().sampleRate(),
                        clone.referenceText()
                );
            }
        }
        if (info != null && info.supportsVoiceClone() && settings.selectedVoiceSample != null && !settings.selectedVoiceSample.isBlank() && voiceLibraryService != null) {
            java.nio.file.Path resolved = voiceLibraryService.resolveVoiceSamplePath(settings.selectedVoiceSample);
            voiceSample = resolved == null ? "" : resolved.toString();
        }
        return new TtsVoiceProfile(voiceStyle, voiceId, (float) settings.speed, settings.speakerId, voiceSample);
    }

    private TtsVoiceCloneRegistry voiceRegistry() {
        if (voiceCloneRegistry == null) {
            voiceCloneRegistry = new TtsVoiceCloneRegistry(env, config);
        }
        return voiceCloneRegistry;
    }

    private static String normalizeVoiceId(String voiceStyle) {
        return voiceStyle == null ? "" : voiceStyle.trim();
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
        context.fail(envelopeId, code, message, null);
    }

    private void publishPlaybackStatus(TtsPlaybackState state) {
        adapter.publishPlaybackStatus(TtsPlaybackStatusPayload.now(state));
    }

}
