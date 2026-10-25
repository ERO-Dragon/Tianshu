package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;
import com.rheinmetal.tianshu.client.settings.module.presence.PresenceSettingsAccess;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The kernel's own settings.
 *
 * <p>Only what the kernel itself owns: the debug switch, the presence HUD and the directory the voice
 * resource layer materialises into. Every feature module keeps its settings in its own file under
 * {@code config/Tianshu/module/<moduleId>.properties}, so adding a module option no longer edits this
 * class — which is the whole reason it shrank from twelve implemented interfaces to three.</p>
 */
public class ClientConfig implements PresenceSettingsAccess, GlobalDebugSettingsAccess {

    private final Path gameConfigDirectory;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;

    public static final ModConfigSpec.BooleanValue PRESENCE_HUD_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_STATUS_TEXT_ENABLED;
    public static final ModConfigSpec.BooleanValue PRESENCE_ICON_ENABLED;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_SIZE_PIXELS;
    public static final ModConfigSpec.EnumValue<PresenceHudVisualPreset> PRESENCE_VISUAL_PRESET;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_POSITION_X;
    public static final ModConfigSpec.DoubleValue PRESENCE_ICON_POSITION_Y;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("debug");
        DEBUG_ENABLED = builder.define("enabled", false);
        builder.pop();

        builder.comment("映迹: player-visible presence HUD").push("presence");
        PRESENCE_HUD_ENABLED = builder.define("hudEnabled", true);
        PRESENCE_STATUS_TEXT_ENABLED = builder.define("statusTextEnabled", true);
        PRESENCE_ICON_ENABLED = builder.define("iconEnabled", true);
        PRESENCE_ICON_SIZE_PIXELS = builder.defineInRange("iconSizePixels", PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS);
        PRESENCE_VISUAL_PRESET = builder.defineEnum("visualPreset", PresenceHudVisualPreset.PRESET_ONE);
        PRESENCE_ICON_POSITION_X = builder.defineInRange("iconPositionX", 0.5D, 0.0D, 1.0D);
        PRESENCE_ICON_POSITION_Y = builder.defineInRange("iconPositionY", -1.0D, -1.0D, 1.0D);
        builder.pop();

        SPEC = builder.build();
    }

    public ClientConfig(Path gameDirectory) {
        this.gameConfigDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("config")
                .resolve("Tianshu");
    }

    @Override
    public boolean isPresenceHudEnabled() {
        return PRESENCE_HUD_ENABLED.get();
    }

    @Override
    public void setPresenceHudEnabled(boolean enabled) {
        PRESENCE_HUD_ENABLED.set(enabled);
    }

    @Override
    public boolean isPresenceStatusTextEnabled() {
        return PRESENCE_STATUS_TEXT_ENABLED.get();
    }

    @Override
    public void setPresenceStatusTextEnabled(boolean enabled) {
        PRESENCE_STATUS_TEXT_ENABLED.set(enabled);
    }

    @Override
    public boolean isPresenceIconEnabled() {
        return PRESENCE_ICON_ENABLED.get();
    }

    @Override
    public void setPresenceIconEnabled(boolean enabled) {
        PRESENCE_ICON_ENABLED.set(enabled);
    }

    @Override
    public double getPresenceIconSize() {
        return clampPosition(PRESENCE_ICON_SIZE_PIXELS.get(), PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS);
    }

    @Override
    public void setPresenceIconSize(double size) {
        PRESENCE_ICON_SIZE_PIXELS.set(clampPosition(size, PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS,
                PresenceHudSettings.MIN_ICON_SIZE_PIXELS, PresenceHudSettings.MAX_ICON_SIZE_PIXELS));
    }

    @Override
    public PresenceHudVisualPreset getPresenceVisualPreset() {
        PresenceHudVisualPreset preset = PRESENCE_VISUAL_PRESET.get();
        return preset == null ? PresenceHudVisualPreset.PRESET_ONE : preset;
    }

    @Override
    public void setPresenceVisualPreset(PresenceHudVisualPreset preset) {
        PRESENCE_VISUAL_PRESET.set(preset == null ? PresenceHudVisualPreset.PRESET_ONE : preset);
    }

    @Override
    public double getPresenceIconPositionX() {
        return clampPosition(PRESENCE_ICON_POSITION_X.get(), 0.5D);
    }

    @Override
    public void setPresenceIconPositionX(double position) {
        PRESENCE_ICON_POSITION_X.set(clampPosition(position, 0.5D));
    }

    @Override
    public double getPresenceIconPositionY() {
        return clampPosition(PRESENCE_ICON_POSITION_Y.get(), -1.0D, -1.0D, 1.0D);
    }

    @Override
    public void setPresenceIconPositionY(double position) {
        PRESENCE_ICON_POSITION_Y.set(clampPosition(position, -1.0D, -1.0D, 1.0D));
    }

    @Override
    public boolean isDebugEnabled() {
        return TianshuBuildProfile.debugBuild() && DEBUG_ENABLED.get();
    }

    @Override
    public void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED.set(enabled);
    }

    public Path getRootPath() {
        return getGameConfigDir().resolve("module");
    }

    public Path getGameConfigDir() {
        return gameConfigDirectory;
    }

    @Override
    public void save() {
        SPEC.save();
    }

    private static double clampPosition(double value, double fallback) {
        return clampPosition(value, fallback, 0.0D, 1.0D);
    }

    private static double clampPosition(double value, double fallback, double min, double max) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
