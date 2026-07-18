package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.asr.audio.AudioCaptureService;
import com.rheinmetal.tianshu.function.asr.audio.AsrAudioPipelineFactory;
import com.rheinmetal.tianshu.function.asr.control.AsrController;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngineBootstrap;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngineBootstrapStatus;
import com.rheinmetal.tianshu.function.asr.engine.AsrHotwordSupport;
import com.rheinmetal.tianshu.function.asr.input.AsrInputGateway;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;
import com.rheinmetal.tianshu.function.asr.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.function.asr.recognition.AsrSpeechSegmenter;
import com.rheinmetal.tianshu.function.asr.recognition.AsrVadSpeechSegmenter;
import com.rheinmetal.tianshu.function.asr.session.AsrSessionManager;
import com.rheinmetal.tianshu.function.asr.state.AsrStateMachine;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceAccess;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class AsrModule implements TianshuManagedModule, AsrModuleRuntimeControl {
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final AsrConfiguration config;
    private final BooleanSupplier voiceInputAcceptance;
    private final LongSupplier interruptProcessing;
    private final AtomicBoolean voiceResourceReloadQueued = new AtomicBoolean(false);
    private final AsrProtocolAdapter adapter;
    private AsrController controller;
    private AsrInputGateway inputGateway;
    private ModuleRuntimeState runtimeState;
    private ModuleRuntimeContext runtimeContext;
    private VoiceResourceAccess voiceResources;
    private Consumer<VoiceResourceSnapshot> voiceResourceListener;
    private volatile AsrEngine engine;
    private volatile ProtocolTaskHandle voiceResourceReloadTask;
    private volatile AudioCaptureService audioCapture;
    private volatile long appliedVoiceResourceVersion = -1L;
    private volatile boolean destroyed;
    private AsrModelService modelService;

    public AsrModule(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env, AsrConfiguration config, BooleanSupplier voiceInputAcceptance, LongSupplier interruptProcessing) {
        this.audioBridge = audioBridge;
        this.moduleRuntime = moduleRuntime;
        this.env = env;
        this.config = config;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.interruptProcessing = interruptProcessing;
        this.adapter = new AsrProtocolAdapter(moduleRuntime);
    }

    @Override
    public String moduleId() {
        return "module.asr";
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        modelService = new AsrModelService(env, config, audioBridge, moduleRuntime, this::asrEngine, this::isAsrReady, this::publishModuleStatus);
        context.services().register(AsrModelService.class, modelService);
        context.services().register(AsrModuleRuntimeControl.class, this);
        inputGateway = new AsrInputGateway(this::canAcceptVoiceInput);
        context.services().register(AsrInputService.class, inputGateway);
        adapter.subscribeRuntimeInterrupt(this::handleRuntimeInterrupt);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        destroyed = false;
        runtimeContext = context;
        runtimeState = context.runtimeState();
        initializeEngine(context);
        bindVoiceResources(context.voiceResources());
        AsrStateMachine stateMachine = new AsrStateMachine();
        AsrSessionManager sessionManager = new AsrSessionManager();
        audioCapture = new AudioCaptureService(audioBridge, env, AsrSpeechSegmenter.disabled());
        reconfigureAudioPipeline();
        AsrRecognitionService recognition = new AsrRecognitionService(env, this::asrEngine, adapter);
        controller = new AsrController(env, config, this::canAcceptVoiceInput, this::isAsrReady, interruptProcessing, adapter, stateMachine, sessionManager, audioCapture, recognition, this::publishModuleStatus);
        if (inputGateway == null) {
            inputGateway = new AsrInputGateway(this::canAcceptVoiceInput);
        }
        inputGateway.bind(controller);
    }

    @Override
    public void stop() {
        if (modelService != null) {
            modelService.stopPreview();
        }
        if (controller != null) {
            controller.stop();
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        unbindVoiceResources();
        ProtocolTaskHandle reloadTask = voiceResourceReloadTask;
        if (reloadTask != null && !reloadTask.isDone()) {
            reloadTask.cancel("ASR module destroyed");
        }
        voiceResourceReloadQueued.set(false);
        stop();
        if (modelService != null) {
            modelService.close();
            modelService = null;
        }
        controller = null;
        audioCapture = null;
        if (inputGateway != null) {
            inputGateway.unbind();
            inputGateway = null;
        }
        runtimeContext = null;
        if (runtimeState != null) {
            runtimeState.capabilities().remove(AsrRuntimeCapabilities.INPUT);
            runtimeState = null;
        }
        AsrEngine currentEngine = engine;
        engine = null;
        if (currentEngine != null) {
            currentEngine.shutdown();
        }
    }

    @Override
    public void releaseInputResources() {
        if (inputGateway != null) {
            inputGateway.cancelVoiceInput();
        }
        if (controller != null) {
            controller.releaseHardware();
            return;
        }
        try {
            audioBridge.releaseCaptureHardware();
        } catch (RuntimeException | LinkageError failure) {
            env.error("tianshu.asr.audio.hardware_release_failed", failure);
        }
    }

    @Override
    public void reconfigureAudioPipeline() {
        AudioCaptureService capture = audioCapture;
        if (capture != null) {
            capture.setFrameProcessor(new AsrAudioPipelineFactory(config, env).create());
            capture.setSpeechSegmenter(createSpeechSegmenter());
        }
    }

    private AsrSpeechSegmenter createSpeechSegmenter() {
        return config.isAsrVadEnabled()
                ? new AsrVadSpeechSegmenter(this::publishSpeechActivity)
                : AsrSpeechSegmenter.disabled();
    }

    private AsrEngine asrEngine() {
        return engine;
    }

    private boolean isAsrReady() {
        return config.isAsrEnabled() && runtimeState != null && runtimeState.capabilities().isReady(AsrRuntimeCapabilities.INPUT);
    }

    private boolean canAcceptVoiceInput() {
        return config.isAsrEnabled() && voiceInputAcceptance.getAsBoolean();
    }

    private void publishSpeechActivity(boolean speaking, long sessionId, long occurredAtMillis) {
        AsrProtocolAdapter currentAdapter = adapter;
        if (currentAdapter == null || sessionId <= 0L) {
            return;
        }
        currentAdapter.publishSpeechActivity(new AsrSpeechActivityPayload(speaking, sessionId, occurredAtMillis));
    }

    private void handleRuntimeInterrupt(com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof RuntimeInterruptPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "runtime interrupt payload is invalid", null);
            return;
        }
        AsrController activeController = controller;
        if (activeController != null) {
            activeController.handleRuntimeInterrupt(payload.sessionId());
        }
    }

    private void initializeEngine(ModuleRuntimeContext context) {
        engine = createEngine(context);
        if (engine != null) {
            appliedVoiceResourceVersion = context.voiceResources().snapshot().version();
        }
    }

    private AsrEngine createEngine(ModuleRuntimeContext context) {
        AsrEngineBootstrap bootstrap = new AsrEngineBootstrap(env, config, this::publishBootstrapStatus);
        return bootstrap.initialize(context, moduleId());
    }

    private void publishBootstrapStatus(AsrEngineBootstrapStatus status) {
        if (status == null) {
            return;
        }
        switch (status.kind()) {
            case READY -> publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), status.messageKey()));
            case WAITING -> publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), status.messageKey()));
            case FAILED -> publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), status.messageKey()));
        }
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }

    private void bindVoiceResources(VoiceResourceAccess resources) {
        unbindVoiceResources();
        voiceResources = resources;
        voiceResourceListener = this::handleVoiceResourceChanged;
        if (voiceResources != null) {
            voiceResources.addChangeListener(voiceResourceListener);
        }
    }

    private void unbindVoiceResources() {
        VoiceResourceAccess resources = voiceResources;
        Consumer<VoiceResourceSnapshot> listener = voiceResourceListener;
        if (resources != null && listener != null) {
            resources.removeChangeListener(listener);
        }
        voiceResources = null;
        voiceResourceListener = null;
    }

    private void handleVoiceResourceChanged(VoiceResourceSnapshot snapshot) {
        if (snapshot == null || destroyed || snapshot.version() <= appliedVoiceResourceVersion) {
            return;
        }
        if (!currentModelHotwordsRequireReload()) {
            appliedVoiceResourceVersion = snapshot.version();
            env.info("ASR model does not require hotword reload, applied voice resource version=" + snapshot.version());
            return;
        }
        submitVoiceResourceReload(snapshot.version());
    }

    private void submitVoiceResourceReload(long requestedVersion) {
        if (!voiceResourceReloadQueued.compareAndSet(false, true)) {
            return;
        }
        publishModuleStatus(ModuleStatuses.waitingKeyed(moduleId(), "tianshu.presence.module.asr.reload_started"));
        voiceResourceReloadTask = moduleRuntime.submit(
                ProtocolTaskSpec.builder()
                        .moduleId(moduleId())
                        .lane(ExecutionLane.MODEL_LOAD)
                        .concurrencyKey(moduleId() + ":engine.reload")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runVoiceResourceReload(requestedVersion)
        );
        if (voiceResourceReloadTask.state() == ProtocolTaskState.REJECTED) {
            voiceResourceReloadQueued.set(false);
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.asr.reload_rejected"));
            env.warn("asr.voice_resource.reload.rejected version=" + requestedVersion);
        }
    }

    private void runVoiceResourceReload(long requestedVersion) {
        try {
            if (destroyed) {
                return;
            }
            ModuleRuntimeContext context = runtimeContext;
            VoiceResourceAccess resources = voiceResources;
            if (context == null || resources == null) {
                return;
            }
            VoiceResourceSnapshot snapshot = resources.snapshot();
            if (snapshot.version() <= appliedVoiceResourceVersion) {
                return;
            }
            reloadEngineForVoiceResources(context, snapshot.version(), requestedVersion);
        } finally {
            voiceResourceReloadQueued.set(false);
            VoiceResourceAccess resources = voiceResources;
            if (!destroyed && resources != null && resources.snapshot().version() > appliedVoiceResourceVersion) {
                submitVoiceResourceReload(resources.snapshot().version());
            }
        }
    }

    private void reloadEngineForVoiceResources(ModuleRuntimeContext context, long snapshotVersion, long requestedVersion) {
        env.info("asr.voice_resource.reload.started requestedVersion=" + requestedVersion + " snapshotVersion=" + snapshotVersion);
        AsrController activeController = controller;
        if (activeController != null) {
            activeController.stop();
        }
        ModuleRuntimeState state = runtimeState;
        if (state != null) {
            state.capabilities().install(AsrRuntimeCapabilities.INPUT, moduleId());
        }
        AsrEngine previousEngine = engine;
        engine = null;
        if (previousEngine != null) {
            previousEngine.shutdown();
        }
        AsrEngine nextEngine = createEngine(context);
        if (destroyed) {
            if (nextEngine != null) {
                nextEngine.shutdown();
            }
            return;
        }
        engine = nextEngine;
        if (nextEngine != null) {
            appliedVoiceResourceVersion = snapshotVersion;
            ModuleRuntimeState readyState = runtimeState;
            if (readyState != null) {
                readyState.capabilities().markReady(AsrRuntimeCapabilities.INPUT, moduleId());
            }
            env.info("asr.voice_resource.reload.completed version=" + snapshotVersion);
            publishModuleStatus(ModuleStatuses.readyKeyed(moduleId(), "tianshu.presence.module.asr.reload_complete"));
        } else {
            ModuleRuntimeState failedState = runtimeState;
            if (failedState != null) {
                failedState.capabilities().markFailed(AsrRuntimeCapabilities.INPUT, moduleId(), "ASR 语音热词资源重载失败");
            }
            env.warn("asr.voice_resource.reload.no_engine version=" + snapshotVersion);
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.presence.module.asr.reload_failed"));
        }
    }

    private boolean currentModelHotwordsRequireReload() {
        String modelName = config.getCustomAsrName();
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return AsrHotwordSupport.fromModelPath(config.getAsrModelPath()).reloadRequired();
    }
}


