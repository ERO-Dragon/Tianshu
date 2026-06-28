package com.rheinmetal.tianshu.client.gui.presence.hud.element;

import java.util.Optional;

public interface PresenceHudElementController {
    Optional<PresenceHudElementFrame> update(long nowMillis);
}
