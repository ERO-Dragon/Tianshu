package com.rheinmetal.tianshu.client.api.settings.layer;

/**
 * Client UI boundary for showing a {@link SettingsLayerModel} over the settings page.
 *
 * <p>The business code describes a layer and cannot draw one; the platform layer can draw a layer and
 * must not own form state. This is the one call between them: the description goes in, a window appears
 * on top of the settings page, and the close hook of the description runs when it goes away.
 *
 * <p>A host must not block and must not mutate the description: it presents it.
 */
@FunctionalInterface
public interface SettingsLayerHost {
    /**
     * Shows the layer over the settings page.
     *
     * @param layer the description to present; already complete for exactly one window
     */
    void open(SettingsLayerModel layer);
}
