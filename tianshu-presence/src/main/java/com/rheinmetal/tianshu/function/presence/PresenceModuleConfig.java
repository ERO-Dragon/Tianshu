package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;
import com.rheinmetal.tianshu.api.config.ModuleConfigStores;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudVisualPreset;
import com.rheinmetal.tianshu.function.presence.settings.PresenceSettingsAccess;

import java.util.Objects;

/**
 * Presence's own settings, in presence's own file.
 *
 * <p>Every key here is a product decision — is the HUD on, which elements, how large, which preset, where the icon
 * sits — so the keys belong to this module and the kernel only supplies the store. The host never learns them, and
 * the values survive the module being extracted: the file is already {@code module.presence.properties}.</p>
 *
 * <p>Reads are clamped on the way out and on the way in, so a hand-edited file cannot put the icon off screen or
 * make it unreadable; a missing key is the default, which is also what an absent feature looks like.</p>
 */
public final class PresenceModuleConfig implements PresenceSettingsAccess, PresenceHudSettings {
    private static final String KEY_HUD_ENABLED = "hud.enabled";
    private static final String KEY_STATUS_TEXT_ENABLED = "hud.status_text.enabled";
    private static final String KEY_ICON_ENABLED = "hud.icon.enabled";
    private static final String KEY_ICON_SIZE = "hud.icon.size";
    private static final String KEY_VISUAL_PRESET = "hud.icon.preset";
    private static final String KEY_ICON_POSITION_X = "hud.icon.x";
    private static final String KEY_ICON_POSITION_Y = "hud.icon.y";

    private final ModuleConfigStore store;
    /**
     * The switch the HUD reads every frame.
     *
     * <p>Cached because this is the one setting on the render hot path: reading the store per frame would take a lock
     * inside the GUI loop just to answer "is the HUD on", which is the question that decides whether there is any work
     * at all. A hand-edited file takes effect on the next start, which is also when the store is loaded.</p>
     */
    private volatile Boolean hudEnabledCache;

    public PresenceModuleConfig(ModuleConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The module's store inside the kernel's layout; the id is this module's, so no other module can collide. */
    public static PresenceModuleConfig create(ModuleConfigStores stores) {
        Objects.requireNonNull(stores, "stores");
        return new PresenceModuleConfig(stores.forModule(PresenceProtocolAdapter.MODULE_ID));
    }

    @Override
    public boolean hudEnabled() {
        Boolean cached = hudEnabledCache;
        if (cached == null) {
            cached = store.getBoolean(KEY_HUD_ENABLED, true);
            hudEnabledCache = cached;
        }
        return cached;
    }

    @Override
    public void setHudEnabled(boolean enabled) {
        store.setBoolean(KEY_HUD_ENABLED, enabled);
        hudEnabledCache = enabled;
    }

    @Override
    public boolean statusTextEnabled() {
        return store.getBoolean(KEY_STATUS_TEXT_ENABLED, true);
    }

    @Override
    public void setStatusTextEnabled(boolean enabled) {
        store.setBoolean(KEY_STATUS_TEXT_ENABLED, enabled);
    }

    @Override
    public boolean iconEnabled() {
        return store.getBoolean(KEY_ICON_ENABLED, true);
    }

    @Override
    public void setIconEnabled(boolean enabled) {
        store.setBoolean(KEY_ICON_ENABLED, enabled);
    }

    @Override
    public double iconSizePixels() {
        return clamp(
                store.getDouble(KEY_ICON_SIZE, DEFAULT_ICON_SIZE_PIXELS),
                DEFAULT_ICON_SIZE_PIXELS,
                MIN_ICON_SIZE_PIXELS,
                MAX_ICON_SIZE_PIXELS
        );
    }

    @Override
    public void setIconSizePixels(double size) {
        store.setDouble(KEY_ICON_SIZE, clamp(size, DEFAULT_ICON_SIZE_PIXELS,
                MIN_ICON_SIZE_PIXELS, MAX_ICON_SIZE_PIXELS));
    }

    @Override
    public PresenceHudVisualPreset visualPreset() {
        return parsePreset(store.getString(KEY_VISUAL_PRESET, ""));
    }

    @Override
    public void setVisualPreset(PresenceHudVisualPreset preset) {
        store.setString(KEY_VISUAL_PRESET, (preset == null ? PresenceHudVisualPreset.PRESET_ONE : preset).name());
    }

    @Override
    public double iconPositionX() {
        return clamp(store.getDouble(KEY_ICON_POSITION_X, 0.5D), 0.5D, 0.0D, 1.0D);
    }

    @Override
    public void setIconPositionX(double position) {
        store.setDouble(KEY_ICON_POSITION_X, clamp(position, 0.5D, 0.0D, 1.0D));
    }

    @Override
    public double iconPositionY() {
        return clamp(store.getDouble(KEY_ICON_POSITION_Y, -1.0D), -1.0D, -1.0D, 1.0D);
    }

    @Override
    public void setIconPositionY(double position) {
        store.setDouble(KEY_ICON_POSITION_Y, clamp(position, -1.0D, -1.0D, 1.0D));
    }

    @Override
    public void save() {
        store.save();
    }

    /** An unknown or hand-written preset falls back instead of failing: a bad value must not hide the HUD. */
    private static PresenceHudVisualPreset parsePreset(String raw) {
        if (raw == null || raw.isBlank()) {
            return PresenceHudVisualPreset.PRESET_ONE;
        }
        try {
            return PresenceHudVisualPreset.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            return PresenceHudVisualPreset.PRESET_ONE;
        }
    }

    private static double clamp(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
