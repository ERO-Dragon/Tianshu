package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import ai.onnxruntime.OrtEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.sentencepiece.SentencePieceProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossModelRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void serviceDelegatesModelResourcesToSingleRuntimeOwner() {
        Field[] fields = MossTtsService.class.getDeclaredFields();

        assertTrue(Arrays.stream(fields).anyMatch(field -> field.getType() == MossModelRuntime.class));
        assertFalse(Arrays.stream(fields).anyMatch(field -> field.getType() == OrtEnvironment.class));
        assertFalse(Arrays.stream(fields).anyMatch(field -> field.getType() == SentencePieceProcessor.class));
        assertFalse(Arrays.stream(fields).anyMatch(field -> Map.class.isAssignableFrom(field.getType())));
    }

    @Test
    void closeIsIdempotentBeforeInitialization() {
        FakeGameEnvironment env = new FakeGameEnvironment();
        MossModelRuntime runtime = new MossModelRuntime(
                env,
                Path.of("missing-model"),
                InferenceResourcePolicy.fixedProcessors(1)
        );

        assertDoesNotThrow(runtime::close);
        assertDoesNotThrow(runtime::close);
    }

    @Test
    void missingModelDirectoryIsReportedAsNotInstalledWithoutBeingCreated() {
        FakeGameEnvironment env = new FakeGameEnvironment();
        Path modelDir = tempDir.resolve("moss-absent");
        MossModelRuntime runtime = new MossModelRuntime(
                env,
                modelDir,
                InferenceResourcePolicy.fixedProcessors(1)
        );

        IOException failure = assertThrows(IOException.class, runtime::initialize);

        assertTrue(failure.getMessage().startsWith("TTS_MOSS_MODEL_NOT_INSTALLED"),
                "the model-not-installed failure code is what the backend has to surface");
        assertFalse(Files.exists(modelDir), "checking for a model must not create its directory");
        // A failed init must leave the runtime able to retry instead of reporting itself initialized.
        assertThrows(IOException.class, runtime::initialize);
        runtime.close();
    }
}
