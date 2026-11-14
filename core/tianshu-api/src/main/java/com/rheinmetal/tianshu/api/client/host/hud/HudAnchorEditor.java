package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * Opens the platform's anchor-position editor for one HUD element.
 *
 * <p>The screen, its widgets, its mouse handling and its components belong to the platform; the position, the size and
 * the clamping belong to the feature, which is why the platform asks a {@link HudAnchorView} instead of reading
 * settings it does not own.</p>
 */
public interface HudAnchorEditor {

    void open(HudAnchorView view);
}
