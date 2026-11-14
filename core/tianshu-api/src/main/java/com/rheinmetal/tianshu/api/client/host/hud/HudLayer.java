package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * One HUD element group a module registers with the platform.
 *
 * <p>The platform calls {@link #render} once per frame per registered layer and knows nothing about its content. A
 * module that is disabled or has nothing to draw returns immediately, which is how "HUD off costs nothing" is
 * expressed: one volatile read, no layout, no allocation.</p>
 *
 * <p>The platform isolates a layer: an exception is counted, and a layer that keeps failing is dropped for the rest of
 * the session instead of breaking the GUI every frame.</p>
 */
public interface HudLayer {

    /** Stable identity, used by the platform to remove the layer it registered. */
    String id();

    /** Lower draws first. */
    default int order() {
        return 0;
    }

    void render(HudFrameContext context, HudSurface surface);

    /**
     * Called when the world session ends: drop animation state so the next world starts as a fresh entrance.
     *
     * <p>Optional because a layer without animation has nothing to forget.</p>
     */
    default void reset() {
    }
}
