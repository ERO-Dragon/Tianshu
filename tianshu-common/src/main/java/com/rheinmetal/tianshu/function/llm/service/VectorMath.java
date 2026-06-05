package com.rheinmetal.tianshu.function.llm.service;

import java.util.List;

public final class VectorMath {

    private VectorMath() {
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }
        return dotProduct(a, b) / (magnitude(a) * magnitude(b));
    }

    public static float dotProduct(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static float magnitude(float[] v) {
        if (v == null || v.length == 0) {
            return 0f;
        }
        float sum = 0f;
        for (float val : v) {
            sum += val * val;
        }
        return (float) Math.sqrt(sum);
    }

    public static float[] normalize(float[] v) {
        if (v == null || v.length == 0) {
            return new float[0];
        }
        float mag = magnitude(v);
        if (mag == 0f) {
            return v;
        }
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i] / mag;
        }
        return result;
    }

    public static List<Float> cosineSimilarityBatch(float[] query, float[][] vectors) {
        if (query == null || vectors == null || vectors.length == 0) {
            return List.of();
        }
        return java.util.Arrays.stream(vectors)
                .map(v -> cosineSimilarity(query, v))
                .toList();
    }
}