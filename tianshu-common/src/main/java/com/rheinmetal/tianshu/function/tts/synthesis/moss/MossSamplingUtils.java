package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class MossSamplingUtils {

    private MossSamplingUtils() {
    }

    public static int argmax(float[] values) {
        int bestIndex = 0;
        float bestValue = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > bestValue) {
                bestValue = values[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public static float[] applyRepetitionPenalty(float[] values, int[] previousTokenIds, float repetitionPenalty) {
        if (previousTokenIds == null || previousTokenIds.length == 0 || repetitionPenalty == 1.0f) {
            return values;
        }

        float[] result = Arrays.copyOf(values, values.length);
        Set<Integer> tokenSet = new HashSet<>();
        for (int tokenId : previousTokenIds) {
            tokenSet.add(tokenId);
        }

        for (int tokenId : tokenSet) {
            if (tokenId < 0 || tokenId >= result.length) {
                continue;
            }
            result[tokenId] = result[tokenId] < 0
                    ? result[tokenId] * repetitionPenalty
                    : result[tokenId] / repetitionPenalty;
        }
        return result;
    }

    public static int argmaxWithRepetitionPenalty(float[] values, Set<Integer> previousTokenSet, float repetitionPenalty) {
        int bestIndex = 0;
        float bestValue = Float.NEGATIVE_INFINITY;
        boolean applyPenalty = previousTokenSet != null && !previousTokenSet.isEmpty() && repetitionPenalty != 1.0f;

        for (int i = 0; i < values.length; i++) {
            float score = values[i];
            if (applyPenalty && previousTokenSet.contains(i)) {
                score = score < 0 ? score * repetitionPenalty : score / repetitionPenalty;
            }
            if (score > bestValue) {
                bestValue = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public static float[] softmax(float[] values) {
        float maxValue = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            maxValue = Math.max(maxValue, value);
        }

        double sum = 0.0;
        double[] exps = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            exps[i] = Math.exp(values[i] - maxValue);
            sum += exps[i];
        }

        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (float) (exps[i] / sum);
        }
        return result;
    }

    public static int sampleFromScores(
            float[] values,
            boolean doSample,
            float temperature,
            int topK,
            float topP,
            Random random
    ) {
        if (!doSample) {
            return argmax(values);
        }
        if (temperature <= 0.0f) {
            throw new IllegalArgumentException("temperature must be positive when doSample=true");
        }

        float[] scores = Arrays.copyOf(values, values.length);
        for (int i = 0; i < scores.length; i++) {
            scores[i] /= temperature;
        }

        if (topK > 0 && topK < scores.length) {
            float[] sortedDesc = Arrays.copyOf(scores, scores.length);
            Arrays.sort(sortedDesc);
            float threshold = sortedDesc[sortedDesc.length - topK];
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] < threshold) {
                    scores[i] = Float.NEGATIVE_INFINITY;
                }
            }
        }

        if (topP > 0.0f && topP < 1.0f) {
            Integer[] indices = new Integer[scores.length];
            for (int i = 0; i < scores.length; i++) {
                indices[i] = i;
            }
            Arrays.sort(indices, (a, b) -> Float.compare(scores[b], scores[a]));

            float[] sortedScores = new float[scores.length];
            for (int i = 0; i < indices.length; i++) {
                sortedScores[i] = scores[indices[i]];
            }

            float[] sortedProbs = softmax(sortedScores);
            boolean[] removeMask = new boolean[indices.length];
            float cumulative = 0.0f;
            for (int i = 0; i < sortedProbs.length; i++) {
                cumulative += sortedProbs[i];
                if (cumulative > topP) {
                    removeMask[i] = true;
                }
            }
            for (int i = removeMask.length - 1; i > 0; i--) {
                removeMask[i] = removeMask[i - 1];
            }
            if (removeMask.length > 0) {
                removeMask[0] = false;
            }
            for (int i = 0; i < removeMask.length; i++) {
                if (removeMask[i]) {
                    scores[indices[i]] = Float.NEGATIVE_INFINITY;
                }
            }
        }

        float[] probabilities = softmax(scores);
        float randomValue = random.nextFloat();
        for (int i = 0; i < probabilities.length; i++) {
            randomValue -= probabilities[i];
            if (randomValue <= 0.0f) {
                return i;
            }
        }
        return argmax(scores);
    }
}
