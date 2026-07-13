package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTensorOwnershipBoundaryTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/moss");

    @Test
    void serviceDelegatesAutoregressiveInferenceToFrameGenerator() throws Exception {
        Field[] fields = MossTtsService.class.getDeclaredFields();
        String serviceSource = Files.readString(SOURCE_ROOT.resolve("MossTtsService.java"), StandardCharsets.UTF_8);

        assertTrue(Arrays.stream(fields).anyMatch(field -> field.getType() == MossFrameGenerator.class));
        assertFalse(serviceSource.contains("retainPastByName("));
        assertFalse(serviceSource.contains("runDecodeStep("));
    }

    @Test
    void globalPastUsesDirectTensorHandleTransfer() throws Exception {
        String generatorSource = Files.readString(SOURCE_ROOT.resolve("MossFrameGenerator.java"), StandardCharsets.UTF_8);

        assertTrue(generatorSource.contains("MossTensorState.takeOutputs("));
        assertFalse(generatorSource.contains("retainPastByName("));
        assertFalse(generatorSource.matches("(?s).*takeOutputs\\([^;]*getValue\\(\\).*"));
    }

    @Test
    void emptyOwnershipStateCanBeReplacedAndClosedRepeatedly() {
        MossTensorState state = new MossTensorState();

        assertDoesNotThrow(() -> state.replaceWith(Map.of()));
        assertTrue(state.tensors().isEmpty());
        assertDoesNotThrow(state::close);
        assertDoesNotThrow(state::close);
    }
}
