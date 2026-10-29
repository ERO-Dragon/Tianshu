package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * Where a module registers its HUD layers and asks the platform's anchor editor to open.
 *
 * <p>The platform renders whatever is registered: it holds no list of features and no knowledge of what a layer
 * draws. A module that never registers costs nothing beyond its own disabled check.</p>
 */
public interface HudHost {

    /** Registers a layer, replacing one with the same id. */
    void addLayer(HudLayer layer);

    /** Removes the layer with this id; an absent id is not an error (a module may stop twice). */
    void removeLayer(String layerId);

    /** The platform's screen for dragging a normalized anchor position. */
    HudAnchorEditor anchorEditor();
}
