package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderParams;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * The icon element: one shader quad, no CPU-side shape drawing.
 *
 * <p>Each preset owns its whole lifecycle in one fragment program: the loading cluster, the handoff dot and the
 * settled orb. {@code Morph} is the one handoff parameter — 1 is the loading cluster, 0 is the settled orb — and
 * the program blends the two internally, so the element never switches programs mid-transition.</p>
 *
 * <p>A missing or failing program draws nothing; there is no Java fallback, because a second, differently rendered
 * icon underneath the shader is what made the visual look blocky in the first place.</p>
 */
public final class PresenceIconElementRenderer implements PresenceHudElementRenderer {
    private final PresenceHudSettings settings;
    private final PresencePartnerIconRenderer partnerRenderer = new PresencePartnerIconRenderer();
    /** One reusable parameter instance: a settled frame hands the same values, so nothing is allocated. */
    private HudShaderParams cachedShaderParams;
    private long cachedShaderKey = Long.MIN_VALUE;

    public PresenceIconElementRenderer(PresenceHudSettings settings) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
    }

    @Override
    public PresenceHudElementType type() {
        return PresenceHudElementType.ICON;
    }

    @Override
    public void render(HudSurface surface, PresenceHudElementFrame frame) {
        if (surface == null || frame == null || frame.state() != PresenceHudElementState.ACTIVE) {
            return;
        }
        PresenceHudVisualParameters parameters = frame.visualParameters();
        if (parameters == null || parameters.layout() == null) {
            return;
        }
        surface.shaderRect(
                programFor(settings.visualPreset()),
                quadLeft(parameters.layout()),
                quadTop(parameters.layout()),
                quadRight(parameters.layout()),
                quadBottom(parameters.layout()),
                shaderParams(parameters, parameters.loadingBlend(), frame.display().backgroundTask())
        );
        // The partner marker is a texture and stays a 2D element; it is drawn after the quad, so it sits on top.
        partnerRenderer.render(surface, parameters.layout(), frame.partner(),
                frame.timing().updatedAtMillis(), frame.timing().wallAtMillis());
    }

    private HudShaderParams shaderParams(
            PresenceHudVisualParameters parameters,
            float morph,
            boolean backgroundTask
    ) {
        PresenceHudVisualState state = parameters.state();
        int shaderState = state == PresenceHudVisualState.LISTENING
                ? PresenceHudVisualState.IDLE.ordinal()
                : state.ordinal();
        int intensityKey = Math.round(parameters.intensity() * 100.0F);
        int speedKey = Math.round(parameters.speed() * 100.0F);
        int convergenceKey = Math.round(parameters.convergence() * 100.0F);
        int pulseKey = Math.round(parameters.pulse() * 100.0F);
        int morphKey = Math.round(morph * 1_000.0F);
        long key = shaderState;
        key = key * 1_001L + intensityKey;
        key = key * 1_001L + speedKey;
        key = key * 1_001L + convergenceKey;
        key = key * 1_001L + pulseKey;
        key = key * 1_001L + morphKey;
        key = key * 2L + (backgroundTask ? 1L : 0L);
        if (cachedShaderParams == null || key != cachedShaderKey) {
            cachedShaderParams = new HudShaderParams(
                    parameters.intensity(),
                    parameters.speed(),
                    parameters.convergence(),
                    parameters.pulse(),
                    1.0F,
                    morph,
                    shaderState,
                    backgroundTask ? 1.0F : 0.0F
            );
            cachedShaderKey = key;
        }
        return cachedShaderParams;
    }

    private static HudProgram programFor(PresenceHudVisualPreset preset) {
        return preset == PresenceHudVisualPreset.PRESET_TWO ? HudProgram.PRESET_TWO : HudProgram.PRESET_ONE;
    }

    private static float quadLeft(PresenceHudLayout layout) {
        return layout.centerX() - layout.sizePixels() / 2.0F;
    }

    private static float quadTop(PresenceHudLayout layout) {
        return layout.centerY() - layout.sizePixels() / 2.0F;
    }

    private static float quadRight(PresenceHudLayout layout) {
        return layout.centerX() + layout.sizePixels() / 2.0F;
    }

    private static float quadBottom(PresenceHudLayout layout) {
        return layout.centerY() + layout.sizePixels() / 2.0F;
    }
}
