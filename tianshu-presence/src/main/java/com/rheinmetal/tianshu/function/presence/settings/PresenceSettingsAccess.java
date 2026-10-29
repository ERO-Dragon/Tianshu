package com.rheinmetal.tianshu.function.presence.settings;

import com.rheinmetal.tianshu.function.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudVisualPreset;

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
