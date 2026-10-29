package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderParams;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * The icon element: one shader quad per layer, no CPU-side shape drawing.
 *
 * <p>The whole icon — the orb, its lobes, the arc, the listening wave and the background-task mark — is drawn in the
 * fragment program. The element only decides which program and which uniforms a frame needs. A missing or failing
 * program therefore draws nothing; there is no Java fallback, because a second, differently rendered icon underneath
 * the shader is what made the visual look blocky in the first place.</p>
 */
public final class PresenceIconElementRenderer implements PresenceHudElementRenderer {
    private final PresenceHudSettings settings;
    private final PresencePartnerIconRenderer partnerRenderer = new PresencePartnerIconRenderer();
    /** One reusable parameter instance: a settled frame hands the same values, so nothing is allocated. */
    private HudShaderParams cachedShaderParams;
    private int cachedShaderKey = Integer.MIN_VALUE;

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
        boolean backgroundTask = frame.display().backgroundTask();
        float loadingOpacity = parameters.loadingBlend();
        if (loadingOpacity > 0.001F) {
            surface.shaderRect(
                    HudProgram.LOADING,
                    quadLeft(parameters.layout()),
                    quadTop(parameters.layout()),
                    quadRight(parameters.layout()),
                    quadBottom(parameters.layout()),
                    shaderParams(parameters, loadingOpacity, PresenceHudVisualState.LOADING, backgroundTask)
            );
        }
        float fluidOpacity = 1.0F - loadingOpacity;
        if (fluidOpacity > 0.001F) {
            PresenceHudVisualState fluidState = parameters.state() == PresenceHudVisualState.LOADING
                    ? PresenceHudVisualState.IDLE
                    : parameters.state();
            surface.shaderRect(
                    programFor(settings.visualPreset()),
                    quadLeft(parameters.layout()),
                    quadTop(parameters.layout()),
                    quadRight(parameters.layout()),
                    quadBottom(parameters.layout()),
                    shaderParams(parameters, fluidOpacity, fluidState, backgroundTask)
            );
        }
        // The partner marker is a texture and stays a 2D element; it is drawn after the quad, so it sits on top.
        partnerRenderer.render(surface, parameters.layout(), frame.partner(),
                frame.timing().updatedAtMillis(), frame.timing().wallAtMillis());
    }

    private HudShaderParams shaderParams(
            PresenceHudVisualParameters parameters,
            float opacity,
            PresenceHudVisualState state,
            boolean backgroundTask
    ) {
        int opacityKey = Math.round(opacity * 1_000.0F);
        int key = (((state.ordinal() * 1_001 + opacityKey) * 997
                + Math.round(parameters.intensity() * 100.0F) * 31
                + Math.round(parameters.speed() * 100.0F)) * 31
                + Math.round(parameters.convergence() * 100.0F)) * 7
                + (backgroundTask ? 1 : 0);
        if (cachedShaderParams == null || key != cachedShaderKey) {
            cachedShaderParams = new HudShaderParams(
                    parameters.intensity(),
                    parameters.speed(),
                    parameters.convergence(),
                    parameters.pulse(),
                    opacity,
                    state.ordinal(),
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
