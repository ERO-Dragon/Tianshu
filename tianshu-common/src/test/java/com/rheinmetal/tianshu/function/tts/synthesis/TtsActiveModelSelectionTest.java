package com.rheinmetal.tianshu.function.tts.synthesis;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsActiveModelSelectionTest {
    @Test
    void runtimeActivationDoesNotMutateConfiguredSelection() {
        AtomicReference<String> configured = new AtomicReference<>("configured-model");
        TtsActiveModelSelection selection = new TtsActiveModelSelection(configured::get);

        selection.activate("preview-model");

        assertEquals("configured-model", configured.get());
        assertEquals("preview-model", selection.currentModelName());
    }

    @Test
    void clearingRuntimeActivationReturnsToConfiguredSelection() {
        AtomicReference<String> configured = new AtomicReference<>("configured-model");
        TtsActiveModelSelection selection = new TtsActiveModelSelection(configured::get);
        selection.activate("preview-model");

        selection.clear();

        assertEquals("configured-model", selection.currentModelName());
    }
}
