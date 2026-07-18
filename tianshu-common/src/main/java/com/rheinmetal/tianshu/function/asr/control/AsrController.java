package com.rheinmetal.tianshu.function.asr.control;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.asr.audio.AudioCaptureService;
import com.rheinmetal.tianshu.function.asr.recognition.AsrRecognitionResult;
import com.rheinmetal.tianshu.function.asr.recognition.AsrRecognitionService;
import com.rheinmetal.tianshu.function.asr.session.AsrSessionManager;
import com.rheinmetal.tianshu.function.asr.state.AsrState;
import com.rheinmetal.tianshu.function.asr.state.AsrStateMachine;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class AsrController {
    private final IGameEnvironment env;
    private final AsrConfiguration config;
    private final BooleanSupplier voiceInputAcceptance;
    private final BooleanSupplier asrReady;
    private final LongSupplier interruptProcessing;
    private final AsrProtocolAdapter adapter;
    private final AsrStateMachine stateMachine;
    private final AsrSessionManager sessionManager;
    private final AudioCaptureService audioCapture;
    private final AsrRecognitionService recognition;
    private final Consumer<ModuleStatus> moduleStatusSink;

    public AsrController(
            IGameEnvironment env,
            AsrConfiguration config,
            BooleanSupplier voiceInputAcceptance,
            BooleanSupplier asrReady,
            LongSupplier interruptProcessing,
            AsrProtocolAdapter adapter,
            AsrStateMachine stateMachine,
            AsrSessionManager sessionManager,
            AudioCaptureService audioCapture,
            AsrRecognitionService recognition
    ) {
        this(env, config, voiceInputAcceptance, asrReady, interruptProcessing, adapter, stateMachine, sessionManager, audioCapture, recognition, null);
    }

    public AsrController(
            IGameEnvironment env,
            AsrConfiguration config,
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
        this.env = env;
        this.config = config;
        this.voiceInputAcceptance = voiceInputAcceptance;
        this.asrReady = asrReady;
        this.interruptProcessing = interruptProcessing;
        this.adapter = adapter;
        this.stateMachine = stateMachine;
        this.sessionManager = sessionManager;
        this.audioCapture = audioCapture;
        this.recognition = recognition;
        this.moduleStatusSink = moduleStatusSink == null ? ignored -> {} : moduleStatusSink;
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
            notifyAsrWaking();
            return;
        }
        TriggerMode mode = config.getTriggerMode();
        if (mode == TriggerMode.PUSH_TO_TALK) {
            beginPtt();
            return;
        }
        ensureStreaming(mode);
    }

    private void endInput() {
        if (config.getTriggerMode() == TriggerMode.PUSH_TO_TALK) {
            endPtt();
        }
    }

    private void commitInput() {
        TriggerMode mode = config.getTriggerMode();
        if (mode == TriggerMode.PUSH_TO_TALK) {
            endPtt();
            return;
        }
        if (!recognition.isStreaming()) {
            ensureStreaming(mode);
            return;
        }
        long sessionId = sessionManager.activeRecognitionSession();
        recognition.forceFlush(sessionId);
    }

    private void cancelInput() {
        sessionManager.reset();
        stopActiveInput();
        stateMachine.reset();
    }

    private void stopActiveInput() {
        audioCapture.stopAll();
        if (recognition.isStreaming()) {
            recognition.stopStreaming();
        }
    }

    private void beginPtt() {
        if (!stateMachine.canBeginCapture()) {
            return;
        }
        if (recognition.isStreaming()) {
            recognition.stopStreaming();
            audioCapture.stopStreamCapture();
        }
        long sessionId = interruptProcessing.getAsLong();
        sessionManager.beginRecognitionSession(sessionId);
        stateMachine.moveTo(AsrState.CAPTURING);
        env.info("asr.input.started sessionId=" + sessionId);
        audioCapture.startPttCapture(sessionId);
    }

    private void endPtt() {
        if (!stateMachine.canEndCapture()) {
            return;
        }
        byte[] audioData = audioCapture.stopPttCapture();
        if (audioData == null || audioData.length == 0) {
            env.warn("asr.input.empty");
            stateMachine.reset();
            return;
        }
        long sessionId = sessionManager.activeRecognitionSession();
        stateMachine.moveTo(AsrState.RECOGNIZING);
        recognition.recognizeComplete(audioData, sessionId, "push_to_talk", this::publishIfCurrent, stateMachine::reset);
    }

    private void ensureStreaming(TriggerMode mode) {
        if (!canAcceptInput()) {
            notifyAsrWaking();
            return;
        }
        if (recognition.isStreaming()) {
            stateMachine.moveTo(AsrState.STREAMING);
            return;
        }
        if (!stateMachine.canStartStreaming()) {
            return;
        }
        long sessionId = sessionManager.activeRecognitionSession();
        if (sessionId == 0L) {
            sessionId = interruptProcessing.getAsLong();
            sessionManager.beginRecognitionSession(sessionId);
        }
        long activeSessionId = sessionId;
        if (!recognition.startStreaming(activeSessionId, this::publishIfCurrent, config.isAsrVadEnabled())) {
            stateMachine.moveTo(AsrState.ERROR);
            return;
        }
        audioCapture.startStreamCapture(activeSessionId,
                (chunk, decision) -> recognition.acceptAudioChunk(chunk, activeSessionId, decision));
        stateMachine.moveTo(AsrState.STREAMING);
        env.info("asr.continuous_input.started mode=" + mode + " sessionId=" + activeSessionId);
    }

    private void publishIfCurrent(AsrRecognitionResult result) {
        if (result == null || !result.hasText()) {
            return;
        }
        if (!sessionManager.isActive(result.sessionId())) {
            env.info("asr.result.stale_discarded sessionId=" + result.sessionId() + " activeSessionId=" + sessionManager.activeRecognitionSession());
            return;
        }
        int turnId = sessionManager.nextTurnId();
        adapter.publishFinalText(new AsrTextPayload(result.text(), result.rawText(), turnId, result.sessionId(), result.inputMode(), System.currentTimeMillis()));
        env.info("asr.result.completed turnId=" + turnId + " sessionId=" + result.sessionId());
    }

    private boolean canAcceptInput() {
        return voiceInputAcceptance.getAsBoolean() && asrReady.getAsBoolean();
    }

    private void notifyAsrWaking() {
        env.warn("asr.input.not_ready");
        moduleStatusSink.accept(ModuleStatuses.waitingKeyed("module.asr", "tianshu.presence.module.asr.waking"));
    }
}


