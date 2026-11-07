package com.rheinmetal.tianshu.function.intake.control;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
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
    /**
     * How long the talk key may be held and still count as a stop gesture.
     *
     * <p>A deliberate product constant, not a setting: below this a press cannot contain a useful command, and
     * keeping it outside the settings page means the gesture behaves the same for every player. Holding at least
     * this long always submits a recognition.</p>
     */
    private static final long TAP_MAX_NANOS = 250_000_000L;

    /** The input mode an injected turn carries, so a diagnostic or a consumer can tell it from speech. */
    public static final String DEBUG_INPUT_MODE = "debug";

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
     * The user's stop gesture; bound by the module, which is the only side that can send an interrupt request.
     *
     * <p>A no-op default keeps the controller usable in tests and in a host without an arbiter: the turn is
     * still discarded, it simply has nobody to tell.</p>
     */
    private Runnable tapHandler = () -> {};
    /** Monotonic start of the current push-to-talk press; {@code 0} when no press is being tracked. */
    private volatile long pressStartedNanos;
    /**
     * The speech turn the player can hear right now, or {@code 0} between turns.
     *
     * <p>One field, not two flags: the listening state and the speech-activity signal always describe the
     * same turn, and ending it twice must be impossible.</p>
     */
    private long listeningSessionId;
    /**
     * The turn whose text was published, or {@code 0} when the current turn has not produced any.
     *
     * <p>It separates "the microphone stopped" from "the turn is over": a turn with text is finished by the
     * arbiter, which is the only part that knows how the turn was resolved.</p>
     */
    private volatile long textPublishedSessionId;

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

    public void handle(AsrInputIntent intent) {
        if (intent == null) {
            return;
        }
        switch (intent) {
            case BEGIN -> beginInput();
            case END -> endInput();
            case CANCEL -> cancelInput();
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
        endCurrentSpeechMark();
        pressStartedNanos = 0L;
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

    private void cancelInput() {
        sessionManager.reset();
        stopActiveInput();
        stateMachine.reset();
    }

    private void stopActiveInput() {
        audioCapture.stopAll();
        endCurrentSpeechMark();
        pressStartedNanos = 0L;
        textPublishedSessionId = 0L;
    }

    private void beginPtt() {
        if (!stateMachine.canBeginCapture()) {
            return;
        }
        pressStartedNanos = System.nanoTime();
        // A previous turn that never reached its end path is closed here, so the listening state cannot
        // outlive the recording it described and a new turn always starts from a clean state.
        endCurrentSpeechMark();
        textPublishedSessionId = 0L;
        long sessionId = interruptProcessing.getAsLong();
        sessionManager.beginRecognitionSession(sessionId);
        stateMachine.moveTo(AsrState.CAPTURING);
        env.info("asr.input.started sessionId=" + sessionId);
        audioCapture.startPttCapture();
        // Published after capture really started: the notice describes the microphone, not the key press.
        publishSpeechStarted(sessionId);
    }

    private void endPtt() {
        if (!stateMachine.canEndCapture()) {
            return;
        }
        // Judge the gesture before touching the audio bridge: a slow stop must not stretch a tap past its window.
        boolean tap = isTapPress();
        byte[] audioData = audioCapture.stopPttCapture();
        long sessionId = sessionManager.activeRecognitionSession();
        if (tap) {
            finishTap(sessionId);
            return;
        }
        if (audioData == null || audioData.length == 0) {
            // Nothing was recorded, so no turn exists for the arbiter to resolve: the mark ends here.
            endSpeechMark(sessionId);
            env.warn("asr.input.empty");
            stateMachine.reset();
            return;
        }
        stateMachine.moveTo(AsrState.RECOGNIZING);
        recognition.recognizeComplete(audioData, sessionId, "push_to_talk", this::publishIfCurrent,
                () -> finishTurn(sessionId));
    }

    /**
     * A press that ends inside the tap window is a stop gesture, not a turn.
     *
     * <p>The recorded audio is discarded, the mark is closed and the module is told; no recognition runs, so a
     * tap can never produce ASR text or an IR result.</p>
     */
    private void finishTap(long sessionId) {
        endSpeechMark(sessionId);
        sessionManager.reset();
        textPublishedSessionId = 0L;
        stateMachine.reset();
        env.info("asr.input.tap");
        try {
            tapHandler.run();
        } catch (RuntimeException failure) {
            env.error("asr.interrupt.failed", failure);
        }
    }

    /** Reads and clears the press start, so one press produces at most one tap. */
    private boolean isTapPress() {
        long started = pressStartedNanos;
        pressStartedNanos = 0L;
        return started > 0L && System.nanoTime() - started <= TAP_MAX_NANOS;
    }

    /**
     * Debug only: feeds one text turn through the same lane a recognition would take.
     *
     * <p>It publishes on {@code INPUT.ASR_FINAL_TEXT}, which is exactly where {@link #publishIfCurrent}
     * puts a recognized sentence, so the repair lane and the arbiter see this turn without knowing where
     * it came from. Nothing here opens the microphone or the listening mark: the injected turn has no
     * audio to end, and its outcome is the arbiter's to report. This is what makes IR and IA testable
     * without a capture device, and deterministic when one exists.</p>
     *
     * @return false when the text is blank, which is the only input this path cannot express
     */
    public boolean injectRecognizedText(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        long sessionId = sessionManager.activeRecognitionSession();
        int turnId = sessionManager.nextTurnId();
        env.diagnostics().publish(DiagnosticEvent.now(
                AsrProtocolAdapter.MODULE_ID,
                "RECOGNITION_COMPLETE",
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                java.util.Map.of(
                        "text", trimmed,
                        "inputMode", DEBUG_INPUT_MODE,
                        "sessionId", Long.toString(sessionId)
                )
        ));
        AsrTextPayload payload = new AsrTextPayload(trimmed, trimmed, turnId, sessionId, DEBUG_INPUT_MODE, System.currentTimeMillis());
        TianshuEnvelope published = adapter.publishFinalText(payload);
        if (textRepair != null) {
            textRepair.accept(payload, published);
        }
        env.info("asr.debug.text_injected turnId=" + turnId + " sessionId=" + sessionId);
        return true;
    }

    /**
     * The listening state stays open until the arbiter resolves the turn.
     *
     * <p>Recognition and arbitration are part of the same speech turn from the player's point of view, so closing the
     * mark at key release would flash "idle" while the turn is still in flight. The arbiter ends it when it publishes
     * the outcome; a turn that produced no text has no outcome to wait for and is ended by {@link #finishTurn(long)}.</p>
     */
    private void publishIfCurrent(AsrRecognitionResult result) {
        if (result == null || !result.hasText()) {
            // finishTurn closes a turn that produced no text; it also owns the state reset.
            return;
        }
        if (!sessionManager.isActive(result.sessionId())) {
            env.info("asr.result.stale_discarded sessionId=" + result.sessionId() + " activeSessionId=" + sessionManager.activeRecognitionSession());
            return;
        }
        int turnId = sessionManager.nextTurnId();
        AsrTextPayload payload = new AsrTextPayload(result.text(), result.rawText(), turnId, result.sessionId(), result.inputMode(), System.currentTimeMillis());
        TianshuEnvelope published = adapter.publishFinalText(payload);
        if (textRepair != null) {
            try {
                textRepair.accept(payload, published);
            } catch (RuntimeException repairFailure) {
                // The raw text is out, but no arbiter will see a repaired turn: close it here instead of
                // leaving the listening mark to its TTL.
                env.error("asr.repair.failed", repairFailure);
                endSpeechMark(result.sessionId());
                env.info("asr.result.completed turnId=" + turnId + " sessionId=" + result.sessionId());
                return;
            }
            textPublishedSessionId = result.sessionId();
            handOffSpeechMark(result.sessionId());
        } else {
            // Without a repair lane no arbitration follows, so this controller is the last one that can close it.
            endSpeechMark(result.sessionId());
        }
        env.info("asr.result.completed turnId=" + turnId + " sessionId=" + result.sessionId());
    }

    /**
     * Hands the mark of a turn with text to the arbiter, which is the only part that knows how it ended.
     *
     * <p>Clearing the id without publishing is what stops this controller from closing the same mark a second
     * time on the next key press; the arbiter publishes the one ENDED that the turn gets.</p>
     */
    private synchronized void handOffSpeechMark(long sessionId) {
        if (listeningSessionId == sessionId) {
            listeningSessionId = 0L;
        }
    }

    /**
     * Closes one recognition turn, whichever way it ended.
     *
     * <p>A turn that published text belongs to the arbiter, so this only resets the state machine. A turn
     * without text is closed here. Both the mark and the reset are scoped to the turn: a callback that
     * arrives after a newer turn already owns the controller changes nothing.</p>
     */
    private void finishTurn(long sessionId) {
        if (!sessionManager.isActive(sessionId)) {
            return;
        }
        if (textPublishedSessionId != sessionId) {
            endSpeechMark(sessionId);
        }
        textPublishedSessionId = 0L;
        stateMachine.reset();
    }

    /** Binds the speech-text repair lane; called by the module after both are constructed. */
    public void bindTextRepair(java.util.function.BiConsumer<AsrTextPayload, TianshuEnvelope> textRepair) {
        this.textRepair = textRepair;
    }

    /** Binds the stop gesture; the module turns it into an interrupt request for the arbiter. */
    public void bindTapHandler(Runnable tapHandler) {
        this.tapHandler = tapHandler == null ? () -> {} : tapHandler;
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
     * Ends whichever speech turn is still open; cancellation, runtime interrupt and module stop use this.
     *
     * <p>These paths stop the microphone themselves, so there is no turn left for an arbiter to resolve and the
     * mark must not outlive them.</p>
     */
    private synchronized void endCurrentSpeechMark() {
        endSpeechMark(listeningSessionId);
    }

    /**
     * Ends the mark of one turn, and only while that turn is the open one.
     *
     * <p>The id check is the point: a late callback from a superseded turn must never close the turn that
     * replaced it. The turn id is cleared first, so a second call for the same turn publishes nothing.</p>
     */
    private synchronized void endSpeechMark(long sessionId) {
        if (sessionId <= 0L || listeningSessionId != sessionId) {
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


