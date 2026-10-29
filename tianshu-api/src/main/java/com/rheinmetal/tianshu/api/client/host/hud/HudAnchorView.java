package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The anchor values the editor shows and writes, supplied by the feature that owns them.
 *
 * <p>A view is created for one editing session by the feature's settings page, so {@link #apply} writes to that page's
 * draft and the page still decides when anything is saved. {@link #resolve} must be the same mapping the HUD itself
 * uses, so the preview can never disagree with the real element: dragging to a place the layout would clamp shows the
 * clamped result here too.</p>
 */
public interface HudAnchorView {

    /** The configured horizontal position, 0..1. */
    double normalizedX();

    /** The configured vertical position; a negative value means "follow the default anchor". */
    double normalizedY();

    /** The element as it would be at a draft position, on a screen of this size. */
    HudAnchorPoint resolve(double normalizedX, double normalizedY, int screenWidth, int screenHeight);

    /** Hands a position the player accepted to whatever owns it; called once, on save. */
    void apply(double normalizedX, double normalizedY);
}
