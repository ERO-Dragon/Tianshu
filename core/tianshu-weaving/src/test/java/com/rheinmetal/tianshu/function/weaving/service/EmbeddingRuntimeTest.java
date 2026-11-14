package com.rheinmetal.tianshu.function.weaving.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingRuntimeTest {

    @Test
    void anUnboundRuntimeIsUnavailableAndRefusesToEmbed() {
        EmbeddingRuntime runtime = new EmbeddingRuntime();

        assertFalse(runtime.available());
        assertEquals(-1, runtime.safeDimension());
        assertThrows(IllegalStateException.class, () -> runtime.embed("hello"));
    }

    @Test
    void bindingAnEngineMakesVectorsAndDimensionsAvailable() throws Exception {
        EmbeddingRuntime runtime = new EmbeddingRuntime();
        runtime.bind(new FixedEmbeddingService());

        assertTrue(runtime.available());
        assertEquals(2, runtime.getEmbeddingDimension());
        assertEquals(2, runtime.safeDimension());
        assertEquals(2, runtime.embed("hello").length);
    }

    @Test
    void unbindingClosesTheLaneAgain() {
        EmbeddingRuntime runtime = new EmbeddingRuntime();
        runtime.bind(new FixedEmbeddingService());
        runtime.bind(null);

        assertFalse(runtime.available());
        assertEquals(-1, runtime.safeDimension());
    }

    private static final class FixedEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{1f, 0f};
        }

        @Override
        public float[][] embed(List<String> texts) {
            return texts.stream().map(ignored -> new float[]{1f, 0f}).toArray(float[][]::new);
        }

        @Override
        public int getEmbeddingDimension() {
            return 2;
        }
    }
}
