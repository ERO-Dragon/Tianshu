package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import ai.onnxruntime.OrtEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.sentencepiece.SentencePieceProcessor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossModelRuntimeTest {
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
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        MossModelRuntime runtime = new MossModelRuntime(
                env,
                new HuggingFaceDownloader(env),
                Path.of("missing-model"),
                InferenceResourcePolicy.fixedProcessors(1)
        );

        assertDoesNotThrow(runtime::close);
        assertDoesNotThrow(runtime::close);
    }
}
