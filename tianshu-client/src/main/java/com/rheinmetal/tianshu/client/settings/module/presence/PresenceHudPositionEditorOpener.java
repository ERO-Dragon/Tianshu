package com.rheinmetal.tianshu.client.settings.module.presence;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Client UI boundary for editing the Presence icon position without exposing game UI types. */
@FunctionalInterface
public interface PresenceHudPositionEditorOpener {
    PresenceHudPositionEditorOpener NOOP = (x, y, apply) -> {
    };

    void open(Supplier<Double> positionX, Supplier<Double> positionY, BiConsumer<Double, Double> apply);
}
