package com.rheinmetal.tianshu.function.echo.synthesis;

import java.util.Objects;

/**
 * A selection backed by a model directory on disk.
 *
 * <p>Equality is the record's: two selections are the same when they resolve to the same directory, the same
 * catalog entry and the same backend type. That is what {@code TtsEngineProvider} compares to decide whether
 * an acquire can keep the backend it already has.</p>
 */
public record LocalTtsBackendSelection(TtsResolvedModel model) implements TtsBackendSelection {

    public LocalTtsBackendSelection {
        Objects.requireNonNull(model, "model");
    }

    @Override
    public TtsBackendType backendType() {
        return model.backendType();
    }

    @Override
    public String label() {
        if (model.modelInfo() != null && model.modelInfo().name != null && !model.modelInfo().name.isBlank()) {
            return model.modelInfo().name;
        }
        return model.modelDir().toString();
    }
}