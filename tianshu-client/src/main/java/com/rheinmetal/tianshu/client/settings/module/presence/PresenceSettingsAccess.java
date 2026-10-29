package com.rheinmetal.tianshu.client.settings.module.presence;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;

/**
 * Presence's settings, as the settings page reads and writes them.
 *
 * <p>Reads are the HUD's own accessors, so the page and the renderer can never disagree about a default; this
 * interface only adds the draft-supporting setters and the one save. The HUD itself only needs
 * {@link PresenceHudSettings}, which is why the renderer never sees this type.</p>
 */
public interface PresenceSettingsAccess extends PresenceHudSettings {
    void setHudEnabled(boolean enabled);

    void setStatusTextEnabled(boolean enabled);

    void setIconEnabled(boolean enabled);

    void setIconSizePixels(double size);

    void setVisualPreset(PresenceHudVisualPreset preset);

    void setIconPositionX(double position);

    void setIconPositionY(double position);

    void save();
}
