package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * A resolved anchor rectangle, in GUI-scaled pixels.
 *
 * @param centreX       horizontal centre
 * @param centreY       vertical centre
 * @param sizePixels    the element's square size
 * @param defaultAnchor whether the element is following its default anchor rather than a stored position
 */
public record HudAnchorPoint(float centreX, float centreY, float sizePixels, boolean defaultAnchor) {
    public HudAnchorPoint {
        sizePixels = Math.max(1.0F, sizePixels);
    }
}
