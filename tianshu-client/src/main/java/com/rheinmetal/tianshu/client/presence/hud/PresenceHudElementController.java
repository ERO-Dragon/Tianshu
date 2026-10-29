package com.rheinmetal.tianshu.client.presence.hud;

import java.util.Optional;

public interface PresenceHudElementController {
    Optional<PresenceHudElementFrame> update(PresenceHudElementUpdateContext context);

    default void reset() {
    }
}
