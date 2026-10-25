package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.assembly.VoiceInputPort;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Maps the NeoForge voice key state to the ASR input contract. */
final class NeoForgeVoiceInputController {
    private final Supplier<Optional<VoiceInputPort>> inputServiceSupplier;
    private final Runnable recordPresenceInput;

    private boolean inputActive;
    NeoForgeVoiceInputController(
            Supplier<Optional<VoiceInputPort>> inputServiceSupplier,
            Runnable recordPresenceInput
    ) {
        this.inputServiceSupplier = Objects.requireNonNull(inputServiceSupplier, "inputServiceSupplier");
        this.recordPresenceInput = Objects.requireNonNull(recordPresenceInput, "recordPresenceInput");
    }

    void tick(boolean keyAvailable, boolean keyDown) {
        Optional<VoiceInputPort> service = inputServiceSupplier.get();
        if (!keyAvailable || service.isEmpty()) {
            reset();
            return;
        }

        VoiceInputPort inputService = service.get();
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

    private void handlePushToTalkMode(VoiceInputPort inputService, boolean keyDown) {
        if (keyDown && !inputActive) {
            inputActive = true;
            dispatch(inputService::beginVoiceInput);
        } else if (!keyDown && inputActive) {
            inputActive = false;
            dispatch(inputService::endVoiceInput);
        }
    }

    private void cancelActiveInput(VoiceInputPort inputService) {
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
