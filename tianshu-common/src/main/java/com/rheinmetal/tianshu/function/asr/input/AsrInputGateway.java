package com.rheinmetal.tianshu.function.asr.input;

import com.rheinmetal.tianshu.function.asr.control.AsrController;
import com.rheinmetal.tianshu.function.asr.control.AsrInputIntent;

import java.util.function.BooleanSupplier;

public final class AsrInputGateway implements AsrInputService {
    private final BooleanSupplier acceptance;
    private volatile AsrController controller;

    public AsrInputGateway(BooleanSupplier acceptance) {
        this.acceptance = acceptance == null ? () -> false : acceptance;
    }

    public void bind(AsrController controller) {
        this.controller = controller;
    }

    public void unbind() {
        this.controller = null;
    }

    @Override
    public boolean canAcceptVoiceInput() {
        return acceptance.getAsBoolean();
    }

    @Override
    public void beginVoiceInput() {
        handle(AsrInputIntent.BEGIN);
    }

    @Override
    public void endVoiceInput() {
        handle(AsrInputIntent.END);
    }

    @Override
    public void commitVoiceInput() {
        handle(AsrInputIntent.COMMIT);
    }

    @Override
    public void cancelVoiceInput() {
        handle(AsrInputIntent.CANCEL);
    }

    private void handle(AsrInputIntent intent) {
        AsrController boundController = controller;
        if (boundController != null) {
            boundController.handle(intent, 0L);
        }
    }
}
