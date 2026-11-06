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
import com.rheinmetal.tianshu.function.ia.contract.DialogueInterruptRequestPayload;
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
    /** The concurrency key of the lane the speech model is read on. */
    private static final String ENGINE_LOAD_KEY_SUFFIX = ":engine.load";
    private final IAudioBridge audioBridge;
    private final ModuleRuntimeAccess moduleRuntime;
    private final IGameEnvironment env;
    private final IntakeConfiguration config;
    private final BooleanSupplier voiceInputAcceptance;
    private final LongSupplier interruptProcessing;
    private final AtomicBoolean engineLoadInFlight = new AtomicBoolean(false);
    /**
     * Guards the hand-off of a freshly loaded engine against teardown.
     *
     * <p>Loading happens on the model lane while teardown runs on the caller's thread, so the two have to
     * agree on one thing: whichever of them is second must not leave a loaded model unreachable. Both the
     * assignment in {@link #runEngineLoad} and the {@code destroyed} flip in {@link #destroy()} happen
     * under this monitor; a load that loses the race shuts its engine down instead of publishing it.
     */
    private final Object engineOwnership = new Object();
    /**
     * Set by {@link #abandonEngineLoad()}: the module stopped, so a load that is still queued must not
     * publish anything. The task itself still runs and hands its permit back.
     */
    private volatile boolean engineLoadAbandoned;
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
        modelService = new AsrModelService(env, config, audioBridge, moduleRuntime, this::publishModuleStatus);
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
            engineLoadAbandoned = false;
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
            // A short press is a stop gesture: the controller discards the turn and this module asks the
            // arbiter to broadcast the request to whoever is addressed right now.
            controller.bindTapHandler(this::requestDialogueInterrupt);
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
        return () -> subscriptions.forEach(InputSignals.Subscription::close);
    }

    /**
     * A short press asks the arbiter to broadcast a stop request; it is not a dialogue turn.
     *
     * <p>The request is fire-and-forget: the arbiter resolves the current target and nobody answers the
     * gesture. When no arbiter is assembled the tap has already discarded its turn, so there is nothing left
     * to do but say so in the log.</p>
     */
    private void requestDialogueInterrupt() {
        if (!adapter.dialogueInterruptAvailable()) {
            env.warn("asr.interrupt.unavailable");
            return;
        }
        adapter.requestDialogueInterrupt(new DialogueInterruptRequestPayload(System.currentTimeMillis()));
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
        // First, so a model read that is queued or still running does not publish into a stopped module.
        // The queued task itself is left to run and hand its native-load permit back.
        abandonEngineLoad();
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
        AsrEngine owned;
        synchronized (engineOwnership) {
            destroyed = true;
            owned = engine;
            engine = null;
        }
        closeInputRoutes();
        textRepairer.destroy();
        try {
            stop();
        } finally {
            if (modelService != null) {
                modelService.close();
                modelService = null;
            }
            // The native release is last and runs even when an earlier cleanup failed: every other owner of
            // this module's state is gone by now, so nothing may skip freeing the model.
            shutdownQuietly(owned);
            controller = null;
            audioCapture = null;
            inputGateway = null;
            runtimeContext = null;
            if (runtimeState != null) {
                runtimeState.capabilities().remove(AsrRuntimeCapabilities.INPUT);
                runtimeState.capabilities().remove(AsrRuntimeCapabilities.MODEL);
                runtimeState = null;
            }
        }
    }

    /** Closes the two ways a key press can reach the controller, before the controller is stopped. */
    private void closeInputRoutes() {
        if (pushToTalkSubscription != null) {
            pushToTalkSubscription.close();
            pushToTalkSubscription = null;
        }
        if (inputGateway != null) {
            inputGateway.unbind();
            inputGateway = null;
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
            env.error("asr.audio.hardware_release_failed", failure);
        }
    }

    @Override
    public void reconfigureAudioPipeline() {
        AudioCaptureService capture = audioCapture;
        if (capture != null) {
            capture.setFrameProcessor(new AsrAudioPipelineFactory(config).create());
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
     * The single entry for every native read of the speech model.
     *
     * <p>Request the permit, re-enter this module's own lane once it is granted, read, and hand the permit
     * back in a {@code finally}. A second request while one is in flight is dropped: the model is read
     * once per enable, not rebuilt afterwards. The request is <em>deferred</em>, not primary — retrieval is
     * a precondition for dialogue, while speech input is an on-demand capability, so ASR queues behind the
     * embedding lane.
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
            // A refusal never took the permit, so handing it back here would release whichever module
            // actually holds it.
            reportEngineLoadRejected("the native model load queue is full");
            return;
        }
        if (outcome == NativeModelLoadQueue.Outcome.PARKED) {
            // Waiting behind another native model is still "loading" to the player, and saying so is what
            // stops a queued model from looking like a missing one.
            adapter.publishLoadingActivity(true);
        }
    }

    /**
     * The permit arrived: the load re-enters this module's own lane instead of running on the giver.
     *
     * <p>A lane rejection is the one exit where the permit was granted but no task exists to return it
     * from its {@code finally}, so this method hands it back itself.
     */
    private void submitEngineLoad(ModuleRuntimeContext context) {
        ProtocolTaskHandle handle = moduleRuntime.submit(engineLoadSpec(), () -> runEngineLoad(context));
        if (handle.state() == ProtocolTaskState.REJECTED) {
            handBackEnginePermit();
            reportEngineLoadRejected("the model-load lane rejected the task");
        }
    }

    /**
     * The one place the model is read.
     *
     * <p>Every exit path — success, teardown, throwable — runs the {@code finally}, which is the only
     * place the native-load permit goes back. Teardown therefore never hands the permit back on behalf of
     * a task that can still run: it marks the load abandoned, and the task returns before reading.
     *
     * <p>The hand-off to {@link #engine} is what the {@link #engineOwnership} monitor is for: a teardown
     * that lands during the read must not leave a loaded model owned by nobody.
     */
    private void runEngineLoad(ModuleRuntimeContext context) {
        AsrEngine unowned = null;
        try {
            if (isEngineLoadAbandoned()) {
                return;
            }
            unowned = createEngine(context);
            synchronized (engineOwnership) {
                if (!isEngineLoadAbandoned()) {
                    engine = unowned;
                    unowned = null;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            env.error("asr.engine.load_failed", failure);
            ModuleRuntimeState state = runtimeState;
            if (state != null) {
                state.capabilities().markFailed(AsrRuntimeCapabilities.MODEL, moduleId(),
                        "ASR engine load failed: " + failure.getMessage());
            }
            publishModuleStatus(ModuleStatuses.failedKeyed(moduleId(), "tianshu.runtime.module.asr.exception"));
        } finally {
            engineLoadInFlight.set(false);
            adapter.publishLoadingActivity(false);
            handBackEnginePermit();
            shutdownQuietly(unowned);
        }
    }

    private boolean isEngineLoadAbandoned() {
        return destroyed || engineLoadAbandoned;
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
     * A load that never queued must not be silent: it reports itself and leaves the MODEL dimension failed, so
     * the key press explains "could not queue" instead of "not installed".
     */
    private void reportEngineLoadRejected(String detail) {
        engineLoadInFlight.set(false);
        adapter.publishLoadingActivity(false);
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
     * Hands the native-load permit back.
     *
     * <p>Only the task that ran the load, or the submission that never produced one, calls this. The
     * queue's own owner check makes it a no-op when this module no longer holds the permit, so a late task
     * can never steal the permit from whichever module was granted it in the meantime.
     */
    private void handBackEnginePermit() {
        if (loadQueue != null) {
            loadQueue.release(moduleId());
        }
    }

    /**
     * Stops the module from publishing a load that is still queued.
     *
     * <p>Nothing is cancelled: cancelling a queued task would leave the native-load permit held by a task
     * that never runs its {@code finally}. The task is harmless after teardown — {@link #runEngineLoad}
     * checks {@link #isEngineLoadAbandoned()} before it reads anything — and returns the permit itself.
     */
    private void abandonEngineLoad() {
        engineLoadAbandoned = true;
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


