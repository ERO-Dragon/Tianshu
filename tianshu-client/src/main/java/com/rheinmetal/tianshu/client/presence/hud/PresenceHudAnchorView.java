package com.rheinmetal.tianshu.client.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorPoint;
import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorView;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The anchor editor's view of the icon: the same layout the HUD draws with, and a write-back to the settings draft.
 *
 * <p>Previewing through the real layout is what keeps the editor honest — dragging to a place the layout would
 * clamp shows the clamped result, so the position the player lets go of is the position they will see.</p>
 *
 * <p>{@link #apply} writes the settings page's draft, not the file: the page still decides when to save, and
 * cancelling the editor leaves the draft exactly as it was.</p>
 */
public final class PresenceHudAnchorView implements HudAnchorView {
    private final PresenceHudSettings settings;
    private final BiConsumer<Double, Double> apply;

    public PresenceHudAnchorView(PresenceHudSettings settings, BiConsumer<Double, Double> apply) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    @Override
    public double normalizedX() {
        return settings.iconPositionX();
    }

    @Override
    public double normalizedY() {
        return settings.iconPositionY();
    }

    @Override
    public HudAnchorPoint resolve(double normalizedX, double normalizedY, int screenWidth, int screenHeight) {
        PresenceHudLayout layout = PresenceHudLayout.resolve(
                draftSettings(normalizedX, normalizedY),
                screenWidth,
                screenHeight
        );
        return new HudAnchorPoint(layout.centerX(), layout.centerY(), layout.sizePixels(), layout.defaultAnchor());
    }

    @Override
    public void apply(double normalizedX, double normalizedY) {
        apply.accept(normalizedX, normalizedY);
    }

    /** The same settings with only the position replaced, so the preview keeps the real size and preset. */
    private PresenceHudSettings draftSettings(double normalizedX, double normalizedY) {
        return new PresenceHudSettings() {
            @Override
            public boolean hudEnabled() {
                return settings.hudEnabled();
            }

            @Override
            public boolean statusTextEnabled() {
                return settings.statusTextEnabled();
            }

            @Override
            public boolean iconEnabled() {
                return settings.iconEnabled();
            }

            @Override
            public double iconSizePixels() {
                return settings.iconSizePixels();
            }

            @Override
            public PresenceHudVisualPreset visualPreset() {
                return settings.visualPreset();
            }

            @Override
            public double iconPositionX() {
                return normalizedX;
            }

            @Override
            public double iconPositionY() {
                return normalizedY;
            }
        };
    }
}
