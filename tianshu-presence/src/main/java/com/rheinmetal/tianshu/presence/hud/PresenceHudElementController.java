package com.rheinmetal.tianshu.presence.hud;

import java.util.Optional;

public interface PresenceHudElementController {
    Optional<PresenceHudElementFrame> update(PresenceHudElementUpdateContext context);

    default void reset() {
    }
}
