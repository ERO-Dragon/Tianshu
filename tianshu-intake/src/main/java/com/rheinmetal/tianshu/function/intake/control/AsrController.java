package com.rheinmetal.tianshu.function.intake.control;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.ia.contract.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.intake.audio.AudioCaptureService;
import com.rheinmetal.tianshu.function.intake.recognition.AsrRecognitionResult;
import com.rheinmetal.tianshu.function.intake.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.function.intake.session.AsrSessionManager;
import com.rheinmetal.tianshu.function.intake.state.AsrState;
import com.rheinmetal.tianshu.function.intake.state.AsrStateMachine;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.intake.contract.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AsrController {
    private final IGameEnvironment env;
    private final BooleanSupplier voiceInputAcceptance;
    private final BooleanSupplier asrReady;
    private final LongSupplier interruptProcessing;
    private final AsrProtocolAdapter adapter;
    private final AsrStateMachine stateMachine;
    private final AsrSessionManager sessionManager;
    private final AudioCaptureService audioCapture;
    private final AsrRecognitionService recognition;
    private final Consumer<ModuleStatus> moduleStatusSink;
    /** Why capture cannot start; null keeps the generic message for callers that do not supply one. */
    private final Supplier<ModuleStatus> notReadyStatus;
    /**
     * Optional hook run right after the raw text is published, carrying the payload and its envelope.
     *
     * <p>Bound by the module rather than injected through the constructor: the controller already has
     * three overloads, and this collaboration is optional — a controller without a repairer still
     * publishes recognisable text.</p>
     */
    private java.util.function.BiConsumer<AsrTextPayload, TianshuEnvelope> textRepair;
    /**
     * The speech turn the player can hear right now, or {@code 0} between turns.
     *
     * <p>One field, not two flags: the listening state and the speech-activity signal always describe the
     * same turn, and ending it twice must be impossible.</p>
     */
    private long listeningSessionId;

    public AsrController(
            IGameEnvironment env,
            BooleanSupplier voiceInputAcceptance,
            BooleanSupplier asrReady,
            LongSupplier interruptProcessing,
            AsrProtocolAdapter adapter,
            AsrStateMachine stateMachine,
            AsrSessionManager sessionManager,
            AudioCaptureService audioCapture,
            AsrRecognitionService recognition
    ) {
        this(env, voiceInputAcceptance, asrReady, interruptProcessing, adapter, stateMachine, sessionManager, audioCapture, recognition, null, null);
    }

    public AsrController(
            IGameEnvironment env,
            BooleanSupplier voiceInputAcceptance,
            BooleanSupplier asrReady,
            LongSupplier interruptProcessing,
            AsrProtocolAdapter adapter,
            AsrStateMachine stateMachine,
            AsrSessionManager sessionManager,
            AudioCaptureService audioCapture,
            AsrRecognitionService recognition,
            Consumer<ModuleStatus> moduleStatusSink
    ) {
        this(env, voiceInputAcceptance, asrReady, interruptProcessing, adapter, stateMachine,
                sessionManager, audioCapture, recognition, moduleStatusSink, null);
    }

    /**
     * @param notReadyStatus why capture cannot start, in the player's terms; supplied by the module
     *                      because only it knows whether the lane is still preparing or has no usable
     *                      model. Required for a usable message: a single generic "waking up" answer to a
     *                      model that is simply not installed is what made a dead key look like a slow
     *                      one.
     */
    public AsrController(
            IGameEnvironment env,
            BooleanSupplier voiceInputAcceptance,
            BooleanSupplier asrReady,
            LongSupplier interruptProcessing,
            AsrProtocolAdapter adapter,
            AsrStateMachine stateMachine,
            AsrSessionManager sessionManager,
            AudioCaptureService audioCapture,
            AsrRecognitionService recognition,
            Consumer<ModuleStatus> moduleStatusSink,
            Supplier<ModuleStatus> notReadyStatus
    ) {
        this.env = env;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.asrReady = asrReady;
        this.interruptProcessing = interruptProcessing;
        this.adapter = adapter;
        this.stateMachine = stateMachine;
        this.sessionManager = sessionManager;
        this.audioCapture = audioCapture;
        this.recognition = recognition;
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
        this.notReadyStatus = notReadyStatus;
    }

    public void handle(AsrInputIntent intent, long eventSessionId) {
        if (intent == null) {
            return;
        }
        switch (intent) {
            case BEGIN -> beginInput();
            case END -> endInput();
            case COMMIT -> commitInput();
            case CANCEL -> cancelInput();
            case INTERRUPT -> handleRuntimeInterrupt(eventSessionId);
        }
    }

    public void handleRuntimeInterrupt(long sessionId) {
        if (sessionManager.isActive(sessionId)) {
            return;
        }
        sessionManager.interrupt(sessionId);
        stopActiveInput();
        stateMachine.reset();
    }

    public void stop() {
        publishSpeechEnded();
        stateMachine.moveTo(AsrState.STOPPING);
        recognition.stopAll();
        audioCapture.stopAll();
        sessionManager.reset();
        stateMachine.reset();
    }

    public void releaseHardware() {
        stop();
        audioCapture.releaseHardware();
    }

    private void beginInput() {
        if (!canAcceptInput()) {
            notifyNotReady();
            return;
        }
        beginPtt();
    }

    private void endInput() {
        endPtt();
    }

    private void commitInput() {
        endPtt();
    }

    private void cancelInput() {
        sessionManager.reset();
        stopActiveInput();
        stateMachine.reset();
    }

    private void stopActiveInput() {
        audioCapture.stopAll();
        publishSpeechEnded();
    }

    private void beginPtt() {
        if (!stateMachine.canBeginCapture()) {
            return;
        }
        // A previous turn that never reached its end path is closed here, so the listening state cannot
        // outlive the recording it described and a new turn always starts from a clean state.
        publishSpeechEnded();
        long sessionId = interruptProcessing.getAsLong();
        sessionManager.beginRecognitionSession(sessionId);
        stateMachine.moveTo(AsrState.CAPTURING);
        env.info("asr.input.started sessionId=" + sessionId);
        audioCapture.startPttCapture(sessionId);
        // Published after capture really started: the notice describes the microphone, not the key press.
        publishSpeechStarted(sessionId);
    }

    private void endPtt() {
        if (!stateMachine.canEndCapture()) {
            return;
        }
        byte[] audioData = audioCapture.stopPttCapture();
        if (audioData == null || audioData.length == 0) {
            // Nothing was recorded, so no turn exists for the arbiter to resolve: the mark ends here.
            publishSpeechEnded();
            env.warn("asr.input.empty");
            stateMachine.reset();
            return;
        }
        long sessionId = sessionManager.activeRecognitionSession();
        stateMachine.moveTo(AsrState.RECOGNIZING);
        recognition.recognizeComplete(audioData, sessionId, "push_to_talk", this::publishIfCurrent, this::endAfterRecognitionFailure);
    }

    /**
     * The listening state stays open until the arbiter resolves the turn.
     *
     * <p>Recognition and arbitration are part of the same speech turn from the player's point of view, so closing the
     * mark at key release would flash "idle" while the turn is still in flight. The arbiter ends it when it publishes
     * the outcome; only a turn that produces no text has to end it here.</p>
     */
    private void publishIfCurrent(AsrRecognitionResult result) {
        if (result == null || !result.hasText()) {
            publishSpeechEnded();
            return;
        }
        if (!sessionManager.isActive(result.sessionId())) {
            publishSpeechEnded();
            env.info("asr.result.stale_discarded sessionId=" + result.sessionId() + " activeSessionId=" + sessionManager.activeRecognitionSession());
            return;
        }
        int turnId = sessionManager.nextTurnId();
        AsrTextPayload payload = new AsrTextPayload(result.text(), result.rawText(), turnId, result.sessionId(), result.inputMode(), System.currentTimeMillis());
        TianshuEnvelope published = adapter.publishFinalText(payload);
        if (textRepair != null) {
            textRepair.accept(payload, published);
        }
        env.info("asr.result.completed turnId=" + turnId + " sessionId=" + result.sessionId());
    }

    /** Recognition failed, so no arbiter will see this turn: close the mark here. */
    private void endAfterRecognitionFailure() {
        publishSpeechEnded();
        stateMachine.reset();
    }

    /** Binds the speech-text repair lane; called by the module after both are constructed. */
    public void bindTextRepair(java.util.function.BiConsumer<AsrTextPayload, TianshuEnvelope> textRepair) {
        this.textRepair = textRepair;
    }

    /**
     * Announces one speech turn to the two consumers that act on it.
     *
     * <p>IA freezes the world snapshot it will arbitrate with at the moment the player starts speaking, so the
     * signal has to exist before recognition produces text; presence shows the listening state for the same
     * turn. Both are published from the one place that knows the microphone is really capturing.</p>
     */
    private synchronized void publishSpeechStarted(long sessionId) {
        if (sessionId <= 0L) {
            return;
        }
        listeningSessionId = sessionId;
        adapter.publishSpeechActivity(AsrSpeechActivityPayload.speaking(sessionId));
        adapter.publishListeningActivity(true, sessionId);
    }

    /**
     * Ends the current speech turn, exactly once, whichever path stopped the microphone.
     *
     * <p>Cancel, runtime interrupt, module stop and an empty recording all reach this; the turn id is cleared
     * first, so a second call publishes nothing.</p>
     */
    private synchronized void publishSpeechEnded() {
        long sessionId = listeningSessionId;
        if (sessionId <= 0L) {
            return;
        }
        listeningSessionId = 0L;
        adapter.publishSpeechActivity(AsrSpeechActivityPayload.silent(sessionId));
        adapter.publishListeningActivity(false, sessionId);
    }

    private boolean canAcceptInput() {
        return voiceInputAcceptance.getAsBoolean() && asrReady.getAsBoolean();
    }

    /**
     * A key press that cannot record is still an event the player needs an answer to, so the reason is
     * re-published here rather than logged only: a message that expires before the press is the same as
     * no message at all.
     */
    private void notifyNotReady() {
        env.warn("asr.input.not_ready");
        ModuleStatus reason = notReadyStatus == null ? null : notReadyStatus.get();
        moduleStatusSink.accept(reason != null
                ? reason
                : ModuleStatuses.waitingKeyed(AsrProtocolAdapter.MODULE_ID, "tianshu.runtime.module.asr.waking"));
    }
}


