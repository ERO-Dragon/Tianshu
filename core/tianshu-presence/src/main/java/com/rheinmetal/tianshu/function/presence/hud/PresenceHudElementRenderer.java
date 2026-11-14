package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * Draws one element type on the platform's surface.
 *
 * <p>The renderer is the only half of the HUD that touches a drawing API, and it lives in the feature because what a
 * state looks like is a product decision. The surface is an api port across the platform boundary.</p>
 */
public interface PresenceHudElementRenderer {
    PresenceHudElementType type();

    void render(HudSurface surface, PresenceHudElementFrame frame);
}
