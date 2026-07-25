package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.asr.input.AsrInputService;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Maps the NeoForge voice key state to the ASR input contract. */
final class NeoForgeVoiceInputController {
    private final Supplier<TriggerMode> triggerModeSupplier;
    private final Supplier<Optional<AsrInputService>> inputServiceSupplier;
    private final Runnable recordPresenceInput;

    private boolean inputActive;
    private boolean alwaysCommitKeyPressed;
    private TriggerMode lastTriggerMode;

    NeoForgeVoiceInputController(
            Supplier<TriggerMode> triggerModeSupplier,
            Supplier<Optional<AsrInputService>> inputServiceSupplier,
            Runnable recordPresenceInput
    ) {
        this.triggerModeSupplier = Objects.requireNonNull(triggerModeSupplier, "triggerModeSupplier");
        this.inputServiceSupplier = Objects.requireNonNull(inputServiceSupplier, "inputServiceSupplier");
        this.recordPresenceInput = Objects.requireNonNull(recordPresenceInput, "recordPresenceInput");
    }

    void tick(boolean keyAvailable, boolean keyDown) {
        Optional<AsrInputService> service = inputServiceSupplier.get();
        if (!keyAvailable || service.isEmpty()) {
            reset();
            return;
        }

        AsrInputService inputService = service.get();
        if (!inputService.canAcceptVoiceInput()) {
            cancelActiveInput(inputService);
            reset();
            return;
        }

        TriggerMode currentMode = Objects.requireNonNull(triggerModeSupplier.get(), "triggerMode");
        if (lastTriggerMode != null && lastTriggerMode != currentMode) {
            cancelActiveInput(inputService);
            resetState();
        }

        switch (currentMode) {
            case ALWAYS -> handleAlwaysMode(inputService, keyDown);
            case PUSH_TO_TALK -> handlePushToTalkMode(inputService, keyDown);
        }
        lastTriggerMode = currentMode;
    }

    void reset() {
        resetState();
        lastTriggerMode = null;
    }

    private void handleAlwaysMode(AsrInputService inputService, boolean keyDown) {
        if (!inputActive) {
            inputActive = true;
            dispatch(inputService::beginVoiceInput);
        }
        if (keyDown && !alwaysCommitKeyPressed) {
            alwaysCommitKeyPressed = true;
            dispatch(inputService::commitVoiceInput);
        } else if (!keyDown) {
            alwaysCommitKeyPressed = false;
        }
    }

    private void handlePushToTalkMode(AsrInputService inputService, boolean keyDown) {
        if (keyDown && !inputActive) {
            inputActive = true;
            dispatch(inputService::beginVoiceInput);
        } else if (!keyDown && inputActive) {
            inputActive = false;
            dispatch(inputService::endVoiceInput);
        }
    }

    private void cancelActiveInput(AsrInputService inputService) {
        if (!inputActive) {
            return;
        }
        inputActive = false;
        dispatch(inputService::cancelVoiceInput);
    }

    private void dispatch(Runnable action) {
        recordPresenceInput.run();
        action.run();
    }

    private void resetState() {
        inputActive = false;
        alwaysCommitKeyPressed = false;
    }
}
