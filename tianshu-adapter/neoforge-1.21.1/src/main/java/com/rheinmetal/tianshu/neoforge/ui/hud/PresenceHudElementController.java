package com.rheinmetal.tianshu.neoforge.ui.hud;

import java.util.Optional;

public interface PresenceHudElementController {
    Optional<PresenceHudElementFrame> update(PresenceHudElementUpdateContext context);

    default void reset() {
    }
}
