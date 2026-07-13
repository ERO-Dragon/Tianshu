package com.rheinmetal.tianshu.function.llm.service;

import java.util.List;

final class LlmEmbeddingServiceAdapter implements EmbeddingService {
    private final LlmInferenceClient inferenceClient;
    private volatile int cachedDimension = -1;

    LlmEmbeddingServiceAdapter(LlmInferenceClient inferenceClient) {
        this.inferenceClient = inferenceClient;
    }

    @Override
    public float[] embed(String text) throws Exception {
        float[] result = inferenceClient.embed(text);
        updateDimension(result);
        return result;
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        float[][] result = inferenceClient.embed(texts);
        if (result != null) {
            for (float[] vector : result) {
                updateDimension(vector);
                if (cachedDimension > 0) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public int getEmbeddingDimension() {
        return cachedDimension;
    }

    private void updateDimension(float[] vector) {
        if (cachedDimension < 0 && vector != null && vector.length > 0) {
            cachedDimension = vector.length;
        }
    }
}
