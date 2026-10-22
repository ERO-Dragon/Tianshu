package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.function.intake.input.AsrInputService;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Maps the NeoForge voice key state to the ASR input contract. */
final class NeoForgeVoiceInputController {
    private final Supplier<Optional<AsrInputService>> inputServiceSupplier;
    private final Runnable recordPresenceInput;

    private boolean inputActive;
    NeoForgeVoiceInputController(
            Supplier<Optional<AsrInputService>> inputServiceSupplier,
            Runnable recordPresenceInput
    ) {
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

        handlePushToTalkMode(inputService, keyDown);
    }

    void reset() {
        resetState();
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
    }
}
