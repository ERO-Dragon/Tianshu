package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.function.intake.repair.AsrTextRepairer;
import com.rheinmetal.tianshu.function.intake.repair.enhance.IrNamedObjectEnhancer;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;
import com.rheinmetal.tianshu.function.intake.audio.AudioCaptureService;
import com.rheinmetal.tianshu.function.intake.audio.AsrAudioPipelineFactory;
import com.rheinmetal.tianshu.function.intake.control.AsrController;
import com.rheinmetal.tianshu.function.intake.engine.AsrEngine;
import com.rheinmetal.tianshu.function.intake.engine.AsrEngineBootstrap;
import com.rheinmetal.tianshu.function.intake.engine.AsrEngineBootstrapStatus;
import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.function.intake.input.AsrInputGateway;

import java.util.ArrayList;
import java.util.List;
import com.rheinmetal.tianshu.function.intake.input.AsrInputService;
import com.rheinmetal.tianshu.function.intake.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.function.intake.session.AsrSessionManager;
import com.rheinmetal.tianshu.function.intake.state.AsrStateMachine;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class AsrModule implements TianshuManagedModule, AsrModuleRuntimeControl {
    /** Queue action name; also what the native-load gate reports to its observers while we are parked. */
    private static final String LOAD_ACTION = "asr-model";
    /** The concurrency key of the lane the speech model is read on, next to the telemetry reload. */
    private static final String ENGINE_LOAD_KEY_SUFFIX = ":engine.load";
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final IntakeConfiguration config;
    private final BooleanSupplier voiceInputAcceptance;
    private final LongSupplier interruptProcessing;
    private final AtomicBoolean engineLoadInFlight = new AtomicBoolean(false);
    /**
     * Guards the native-load permit so that exactly one hand-back happens no matter how the load ends.
     *
     * <p>Two paths can reach the release: the lane task's own {@code finally}, and teardown cancelling a
     * task that never started. Both flip this flag with a compare-and-set, so the second of them is a
     * no-op instead of a second release that would hand someone else's permit away.
     */
    private final AtomicBoolean engineLoadPermit = new AtomicBoolean(false);
    /** Process-wide serial gate for native model loads; null keeps this module self-contained. */
    private final NativeModelLoadQueue loadQueue;
    private final AsrProtocolAdapter adapter;
    /** Repairs misheard text and reports what it matched; the former standalone IR module. */
    private final AsrTextRepairer textRepairer;
    private AsrController controller;
    private AsrInputGateway inputGateway;
    private InputSignals.Subscription pushToTalkSubscription;
    private ModuleRuntimeState runtimeState;
    private ModuleRuntimeContext runtimeContext;
    private volatile AsrEngine engine;
    /**
     * How the speech-engine lane last ended, kept so a voice key press can say why nothing is happening.
     *
     * <p>The engine bootstrap already publishes this once at prepare time, but that status expires: the
     * player presses the key long after it faded, and "the module is waking up" would be a lie about a
     * model that is simply not installed. Re-publishing the last real outcome is what turns a silent
     * dead key into an actionable message.
     */
    private volatile ModuleStatus engineStatus;
    /** The queued/loading engine task, kept so teardown can cancel a load that has not started. */
    private volatile ProtocolTaskHandle engineLoadTask;
    private volatile AudioCaptureService audioCapture;
    private volatile boolean destroyed;
    private AsrModelService modelService;
    private volatile long lifecycleStatusGeneration;
    private volatile boolean lifecycleActive;

    public AsrModule(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env, IntakeConfiguration config, BooleanSupplier voiceInputAcceptance, LongSupplier interruptProcessing) {
        this(audioBridge, moduleRuntime, env, config, voiceInputAcceptance, interruptProcessing, null, null);
    }

    /**
     * @param loadQueue the process-wide serial gate every native model load goes through; the speech
     *                  model is a deferred request there, because it is an on-demand capability while
     *                  retrieval is a precondition for dialogue. Null keeps this module self-contained.
     */
    public AsrModule(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                     IntakeConfiguration config, BooleanSupplier voiceInputAcceptance,
                     LongSupplier interruptProcessing, NativeModelLoadQueue loadQueue) {
        this(audioBridge, moduleRuntime, env, config, voiceInputAcceptance, interruptProcessing, loadQueue, null);
    }

    /**
     * @param namedObjectEnhancer repairs game terms in the recognised text; null restricts the repair
     *                            lane to the registered voice triggers
     */
    public AsrModule(IAudioBridge audioBridge, ModuleRuntimeAccess moduleRuntime, IGameEnvironment env,
                     IntakeConfiguration config, BooleanSupplier voiceInputAcceptance,
                     LongSupplier interruptProcessing, NativeModelLoadQueue loadQueue,
                     IrNamedObjectEnhancer namedObjectEnhancer) {
        this.audioBridge = audioBridge;
        this.moduleRuntime = moduleRuntime;
        this.env = env;
        this.config = config;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.interruptProcessing = interruptProcessing;
        this.loadQueue = loadQueue;
        this.adapter = new AsrProtocolAdapter(moduleRuntime);
        this.textRepairer = new AsrTextRepairer(adapter, namedObjectEnhancer);
    }

    @Override
    public String moduleId() {
        return AsrProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerOwnedTopics(context.protocol());
        modelService = new AsrModelService(env, config, audioBridge, moduleRuntime, this::asrEngine, this::isAsrReady, this::publishModuleStatus);
        context.services().register(AsrModelService.class, modelService);
        context.services().register(AsrModuleRuntimeControl.class, this);
        inputGateway = new AsrInputGateway(this::canAcceptVoiceInput);
        context.services().register(AsrInputService.class, inputGateway);
        pushToTalkSubscription = subscribePushToTalk();
        adapter.subscribeRuntimeInterrupt(this::handleRuntimeInterrupt);
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        lifecycleStatusGeneration++;
        lifecycleActive = true;
        publishModuleStatus(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleStatusGeneration));
        try {
            destroyed = false;
            runtimeContext = context;
            runtimeState = context.runtimeState();
            engineStatus = null;
                textRepairer.initialize(context.triggers(), context.diagnostics());
            AsrStateMachine stateMachine = new AsrStateMachine();
            AsrSessionManager sessionManager = new AsrSessionManager();
            audioCapture = new AudioCaptureService(audioBridge, env);
            reconfigureAudioPipeline();
            AsrRecognitionService recognition = new AsrRecognitionService(env, this::asrEngine, adapter);
            controller = new AsrController(env, this::canAcceptVoiceInput, this::isAsrReady, interruptProcessing, adapter, stateMachine, sessionManager, audioCapture, recognition, this::publishModuleStatus, this::notReadyStatus);
            // Same call stack as recognition: the repaired analysis is published right after the raw text,
            // instead of being routed through a subscription into a module that had to ask for context first.
            controller.bindTextRepair(textRepairer::process);
            if (inputGateway == null) {
                inputGateway = new AsrInputGateway(this::canAcceptVoiceInput);
            }
            inputGateway.bind(controller);
            // The input channel exists from here on: a key press reaches the controller and gets an
            // answer. Whether recognition can run is the separate MODEL dimension, so a missing model
            // no longer makes the whole channel look dead.
            if (config.isAsrEnabled()) {
                context.runtimeState().capabilities().markReady(AsrRuntimeCapabilities.INPUT, moduleId());
            }
            publishModuleStatus(ModuleStatuses.lifecycleReady(moduleId(), lifecycleStatusGeneration));
            // Queued, never awaited: the model read belongs to the ASR lane and the native-load gate, so
            // preparing the module never blocks the caller on an ONNX read. The outcome arrives through
            // the MODEL capability and the status channel, exactly like the speech backend of TTS.
            requestEngineLoad(context);
        } catch (RuntimeException | Error failure) {
            publishModuleStatus(ModuleStatuses.lifecycleFailed(moduleId(), "tianshu.runtime.module.asr.failed", lifecycleStatusGeneration));
            throw failure;
        }
    }

    /**
     * The push-to-talk gesture arrives as an input signal now: the platform publishes, this module subscribes,
     * and neither names the other.
     *
     * <p>The guard that used to sit on the platform's side moves here, where the answer lives — a module that
     * cannot take input simply ignores the signal instead of the platform asking first.</p>
     */
    private InputSignals.Subscription subscribePushToTalk() {
        List<InputSignals.Subscription> subscriptions = new ArrayList<>();
        subscriptions.add(InputSignals.subscribe(InputSignals.PUSH_TO_TALK_DOWN,
                ignored -> withAcceptableInput(AsrInputService::beginVoiceInput)));
        subscriptions.add(InputSignals.subscribe(InputSignals.PUSH_TO_TALK_UP,
                ignored -> withAcceptableInput(AsrInputService::endVoiceInput)));
        subscriptions.add(InputSignals.subscribe(InputSignals.PUSH_TO_TALK_CANCEL,
                ignored -> withAcceptableInput(AsrInputService::cancelVoiceInput)));
        return () -> subscriptions.forEach(InputSignals.Subscription::close);
    }

    private void withAcceptableInput(java.util.function.Consumer<AsrInputService> action) {
        AsrInputGateway gateway = inputGateway;
        if (gateway == null || !gateway.canAcceptVoiceInput()) {
            return;
        }
        action.accept(gateway);
    }

    @Override
    public void stop() {
        // First, so no native read starts or continues into a session that is going away, and so a permit
        // held for a load that will never run is handed back.
        cancelEngineLoad();
        textRepairer.stop();
        if (modelService != null) {
            modelService.stopPreview();
        }
        if (controller != null) {
            controller.stop();
        }
        if (lifecycleActive) {
            lifecycleActive = false;
            publishModuleStatus(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleStatusGeneration));
        }
    }

    @Override
    public void destroy() {
        destroyed = true;
        textRepairer.destroy();
        // Covers the initial read and a hotword refresh alike: they share one latch and one lane task.
        cancelEngineLoad();
        stop();
        if (modelService != null) {
            modelService.close();
            modelService = null;
        }
        controller = null;
        audioCapture = null;
        if (pushToTalkSubscription != null) {
            pushToTalkSubscription.close();
            pushToTalkSubscription = null;
        }
        if (inputGateway != null) {
            inputGateway.unbind();
            inputGateway = null;
        }
        runtimeContext = null;
        if (runtimeState != null) {
            runtimeState.capabilities().remove(AsrRuntimeCapabilities.INPUT);
            runtimeState.capabilities().remove(AsrRuntimeCapabilities.MODEL);
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
        }
    }

    private AsrEngine asrEngine() {
        return engine;
    }

    /**
     * Whether recognition can run right now: the module online <em>and</em> a speech model loaded.
     *
     * <p>Both dimensions are required here, but they are reported separately, so this returning false
     * never means "the input channel is gone".
     */
    private boolean isAsrReady() {
        if (!config.isAsrEnabled() || runtimeState == null) {
            return false;
        }
        return runtimeState.capabilities().isReady(AsrRuntimeCapabilities.INPUT)
                && runtimeState.capabilities().isReady(AsrRuntimeCapabilities.MODEL);
    }

    /**
     * Why a voice key press cannot record anything, in the player's terms.
     *
     * <p>A module that is still preparing gets the "waking up" message; a module that is online but has
     * no usable model re-publishes the engine's last real outcome (not configured, not installed, failed),
     * which is the only one of the two the player can act on.
     */
    private ModuleStatus notReadyStatus() {
        ModuleRuntimeState state = runtimeState;
        if (state == null || !state.capabilities().isReady(AsrRuntimeCapabilities.INPUT)) {
            return ModuleStatuses.waitingKeyed(moduleId(), "tianshu.runtime.module.asr.waking");
        }
        if (engineLoadInFlight.get()) {
            // The model is queued or being read right now. Reporting "not installed" here would send the
            // player off to re-download a model that is already there.
            return ModuleStatuses.waitingKeyed(moduleId(), "tianshu.runtime.module.asr.loading");
        }
        ModuleStatus last = engineStatus;
        return last != null
                ? last
                : ModuleStatuses.waitingKeyed(moduleId(), "tianshu.runtime.module.asr.not_installed");
    }

    private boolean canAcceptVoiceInput() {
        return config.isAsrEnabled() && voiceInputAcceptance.getAsBoolean();
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

    /**
     * Asks the native-load gate for permission to read the speech model, on the ASR lane.
     *
     * <p>This is the shape the TTS backend already uses: request the permit and let the gate decide when
     * the load may run. The request is <em>deferred</em>, not primary — retrieval is a precondition for
     * dialogue, while speech input is an on-demand capability, so ASR queues behind the embedding lane.
     * Within the deferred tier there is deliberately no ordering: whoever asks first is granted first.
     */
    /**
     * The single entry for every native read of the speech model.
     *
     * <p>Request the permit, re-enter this module's own lane once it is granted, read, and hand the permit
     * back in a {@code finally}. A second request while one is in flight is dropped: the model is read
     * once per enable, not rebuilt afterwards.
     */
    private void requestEngineLoad(ModuleRuntimeContext context) {
        if (!engineLoadInFlight.compareAndSet(false, true)) {
            return;
        }
        if (loadQueue == null) {
            submitEngineLoad(context);
            return;
        }
        NativeModelLoadQueue.Outcome outcome = loadQueue.request(
                moduleId(), LOAD_ACTION, () -> submitEngineLoad(context), NativeModelLoadQueue.Tier.LAST);
        if (outcome == NativeModelLoadQueue.Outcome.REFUSED) {
            failEngineLoad(context, "the native model load queue is full");
            return;
        }
        if (outcome == NativeModelLoadQueue.Outcome.PARKED) {
            // Waiting behind another native model is still "loading" to the player, and saying so is what
            // stops a queued model from looking like a missing one.
            adapter.publishLoadingActivity(true);
        }
    }

    /** The permit arrived: the load re-enters this module's own lane instead of running on the giver. */
    private void submitEngineLoad(ModuleRuntimeContext context) {
        engineLoadPermit.set(true);
        ProtocolTaskHandle handle = moduleRuntime.submit(engineLoadSpec(), () -> runEngineLoad(context));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            engineLoadTask = null;
            failEngineLoad(context, "the model-load lane rejected the task");
            return;
        }
        engineLoadTask = handle;
    }

    /**
     * The one place the model is read. Every exit path — success, early return, throwable — runs the
     * {@code finally}, which is what guarantees the permit goes back to the gate.
     *
     * <p>A hotword reload swaps only after the replacement is up: the engine that is already serving stays
     * alive until then, so a refresh never opens a window with no engine. A failed reload therefore leaves
     * the lane exactly as it was, and the MODEL dimension is put back to ready, because the bootstrap's
     * failure marking describes the replacement that failed, not the engine still running.
     */
    private void runEngineLoad(ModuleRuntimeContext context) {
        try {
            if (destroyed) {
                return;
            }
            AsrEngine previous = engine;
            AsrEngine loaded = createEngine(context);
            if (destroyed) {
                shutdownQuietly(loaded);
                return;
            }
            engine = loaded;
        } catch (RuntimeException | LinkageError failure) {
            env.error("asr.engine.load_failed", failure);
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.runtime.module.asr.exception"));
        } finally {
            engineLoadInFlight.set(false);
            adapter.publishLoadingActivity(false);
            releaseEnginePermit();
        }
    }

    /** The replacement failed but the engine that was serving is still up: the lane keeps working. */
    private void restoreModelReadyIfServing(AsrEngine serving) {
        if (serving == null) {
            return;
        }
        ModuleRuntimeState state = runtimeState;
        if (state != null) {
            state.capabilities().install(AsrRuntimeCapabilities.MODEL, moduleId());
            state.capabilities().markReady(AsrRuntimeCapabilities.MODEL, moduleId());
        }
        engineStatus = ModuleStatuses.readyKeyed(moduleId(), "tianshu.runtime.module.asr.ready");
    }

    private void shutdownQuietly(AsrEngine target) {
        if (target == null) {
            return;
        }
        try {
            target.shutdown();
        } catch (RuntimeException failure) {
            env.warn("asr.engine.shutdown_failed: " + failure.getMessage());
        }
    }

    /**
     * A load that never queued must not be silent: it reports itself and, when no engine exists yet, leaves
     * the MODEL dimension failed so the key press explains "could not queue" instead of "not installed".
     *
     * <p>A rejected hotword refresh is different: the engine that is already serving is untouched, so the
     * refresh is reported as failed without downgrading a lane that still works.
     */
    private void failEngineLoad(ModuleRuntimeContext context, String detail) {
        engineLoadInFlight.set(false);
        adapter.publishLoadingActivity(false);
        releaseEnginePermit();
        env.warn("asr.engine.load_rejected: " + detail);
        ModuleRuntimeState state = runtimeState;
        if (state != null) {
            state.capabilities().markFailed(AsrRuntimeCapabilities.MODEL, moduleId(), detail);
        }
        ModuleStatus failed = ModuleStatuses.failedKeyed(moduleId(), "tianshu.runtime.module.asr.load_rejected");
        engineStatus = failed;
        publishModuleStatus(failed);
    }

    /**
     * Hands the permit back at most once across the load task and teardown.
     *
     * <p>The compare-and-set is the whole point: teardown may cancel a task that never started (whose
     * {@code finally} therefore never runs) while a task that did start releases in its own {@code finally}.
     * Whichever happens second finds the flag already clear and does nothing.
     */
    private void releaseEnginePermit() {
        if (loadQueue != null && engineLoadPermit.compareAndSet(true, false)) {
            loadQueue.release(moduleId());
        }
    }

    /**
     * Stops a load that is still queued or running.
     *
     * <p>A task that never started cannot release in its own {@code finally}, so the permit is handed back
     * here. A task that is already running releases its own instead: handing the permit back from teardown
     * while the native read continues would let the next model start on top of it, which is the one thing
     * the gate exists to prevent.
     */
    private void cancelEngineLoad() {
        ProtocolTaskHandle handle = engineLoadTask;
        engineLoadTask = null;
        boolean wasRunning = handle != null && handle.isRunning();
        if (handle != null && !handle.isDone()) {
            handle.cancel("ASR module stopped");
        }
        if (!wasRunning) {
            releaseEnginePermit();
        }
        engineLoadInFlight.set(false);
        adapter.publishLoadingActivity(false);
    }

    private ProtocolTaskSpec engineLoadSpec() {
        return ProtocolTaskSpec.builder()
                .moduleId(moduleId())
                .lane(ExecutionLane.MODEL_LOAD)
                .concurrencyKey(moduleId() + ENGINE_LOAD_KEY_SUFFIX)
                .maxConcurrency(1)
                .queueCapacity(1)
                .build();
    }

    private AsrEngine createEngine(ModuleRuntimeContext context) {
        AsrEngineBootstrap bootstrap = new AsrEngineBootstrap(
                env,
                config,
                this::publishBootstrapStatus,
                adapter::publishLoadingActivity
        );
        return bootstrap.initialize(context, moduleId());
    }

    private void publishBootstrapStatus(AsrEngineBootstrapStatus status) {
        if (status == null) {
            return;
        }
        ModuleStatus mapped = switch (status.kind()) {
            case READY -> ModuleStatuses.readyKeyed(moduleId(), status.messageKey());
            case WAITING -> ModuleStatuses.waitingKeyed(moduleId(), status.messageKey());
            case FAILED -> ModuleStatuses.failedKeyed(moduleId(), status.messageKey());
        };
        engineStatus = mapped;
        publishModuleStatus(mapped);
    }

    private void publishModuleStatus(ModuleStatus status) {
        if (status != null) {
            adapter.publishModuleStatus(status);
        }
    }
}


