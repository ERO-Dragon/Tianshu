package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtSession;
import com.google.gson.JsonArray;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns the native tensor handles that form one autoregressive cache generation. */
final class MossTensorState implements AutoCloseable {
    private Map<String, OnnxTensor> tensors = Map.of();

    Map<String, OnnxTensor> tensors() {
        return tensors;
    }

    void replaceWith(Map<String, OnnxTensor> next) {
        Objects.requireNonNull(next, "next");
        Map<String, OnnxTensor> previous = tensors;
        tensors = next.isEmpty() ? Map.of() : new HashMap<>(next);
        closeDistinct(previous);
    }

    /**
     * Transfers output tensor handles into a new owner without materializing their values.
     * The caller must not close the result as a whole after this transfer; non-transferred
     * outputs remain the caller's responsibility.
     */
    static Map<String, OnnxTensor> takeOutputs(
            OrtSession.Result result,
            JsonArray outputNames,
            String sourcePrefix,
            String targetPrefix
    ) throws Exception {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(outputNames, "outputNames");
        Map<String, OnnxTensor> taken = new HashMap<>();
        try {
            for (int index = 1; index < outputNames.size(); index++) {
                String outputName = outputNames.get(index).getAsString();
                OnnxValue value = result.get(outputName).orElseThrow();
                if (!(value instanceof OnnxTensor tensor)) {
                    throw new IllegalArgumentException(
                            "Unexpected past tensor output type for " + outputName + ": " + value.getClass()
                    );
                }
                taken.put(outputName.replace(sourcePrefix, targetPrefix), tensor);
            }
            return taken;
        } catch (Exception failure) {
            closeDistinct(taken);
            throw failure;
        }
    }

    @Override
    public void close() {
        Map<String, OnnxTensor> owned = tensors;
        tensors = Map.of();
        closeDistinct(owned);
    }

    private static void closeDistinct(Map<String, OnnxTensor> owned) {
        if (owned == null || owned.isEmpty()) {
            return;
        }
        IdentityHashMap<OnnxTensor, Boolean> closed = new IdentityHashMap<>();
        for (OnnxTensor tensor : owned.values()) {
            if (tensor == null || closed.put(tensor, Boolean.TRUE) != null) {
                continue;
            }
            try {
                tensor.close();
            } catch (Exception ignored) {
                // Native resource cleanup is best effort; all handles are still relinquished.
            }
        }
    }
}
