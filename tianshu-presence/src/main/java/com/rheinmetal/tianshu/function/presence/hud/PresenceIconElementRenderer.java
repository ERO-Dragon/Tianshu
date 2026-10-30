package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderParams;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * The icon element: one shader quad per layer, no CPU-side shape drawing.
 *
 * <p>The whole icon — the fluid bubble, the volumetric orb, the loading cluster, the listening ripple and the
 * background-task mark — is drawn in the fragment program. The element only decides which program and which
 * uniforms a frame needs. A missing or failing program therefore draws nothing; there is no Java fallback, because
 * a second, differently rendered icon underneath the shader is what made the visual look blocky in the first place.</p>
 *
 * <p>Loading hands off through one solid dot. The transition's loading blend is split at its midpoint: the outgoing
 * layer collapses into the dot, then the incoming layer grows out of it. Only one program is drawn at a time, so the
 * handoff is a shape change rather than a cross-fade, and both programs meet at the same dot.</p>
 */
public final class PresenceIconElementRenderer implements PresenceHudElementRenderer {
    private static final float MORPH_SWITCH = 0.5F;
    private static final float SETTLED_MORPH = 0.0F;

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
        handoff(surface, parameters, frame.display().backgroundTask());
        // The partner marker is a texture and stays a 2D element; it is drawn after the quad, so it sits on top.
        partnerRenderer.render(surface, parameters.layout(), frame.partner(),
                frame.timing().updatedAtMillis(), frame.timing().wallAtMillis());
    }

    /**
     * Draws exactly one shader layer. {@code morph} is 1 at the handoff dot and 0 at the settled shape; the loading
     * and the preset program both understand it, so the switch at {@link #MORPH_SWITCH} is invisible.
     */
    private void handoff(HudSurface surface, PresenceHudVisualParameters parameters, boolean backgroundTask) {
        float blend = parameters.loadingBlend();
        boolean loadingTarget = parameters.state() == PresenceHudVisualState.LOADING;
        if (loadingTarget) {
            if (blend < MORPH_SWITCH) {
                drawPreset(surface, parameters, blend * 2.0F, backgroundTask);
            } else {
                drawLoading(surface, parameters, 2.0F - blend * 2.0F, backgroundTask);
            }
            return;
        }
        if (blend > 0.001F) {
            if (blend > MORPH_SWITCH) {
                drawLoading(surface, parameters, 2.0F - blend * 2.0F, backgroundTask);
            } else {
                drawPreset(surface, parameters, blend * 2.0F, backgroundTask);
            }
            return;
        }
        drawPreset(surface, parameters, SETTLED_MORPH, backgroundTask);
    }

    private void drawLoading(
            HudSurface surface,
            PresenceHudVisualParameters parameters,
            float morph,
            boolean backgroundTask
    ) {
        surface.shaderRect(
                HudProgram.LOADING,
                quadLeft(parameters.layout()),
                quadTop(parameters.layout()),
                quadRight(parameters.layout()),
                quadBottom(parameters.layout()),
                shaderParams(parameters, morph, PresenceHudVisualState.LOADING, backgroundTask)
        );
    }

    private void drawPreset(
            HudSurface surface,
            PresenceHudVisualParameters parameters,
            float morph,
            boolean backgroundTask
    ) {
        PresenceHudVisualState state = parameters.state() == PresenceHudVisualState.LOADING
                ? PresenceHudVisualState.IDLE
                : parameters.state();
        surface.shaderRect(
                programFor(settings.visualPreset()),
                quadLeft(parameters.layout()),
                quadTop(parameters.layout()),
                quadRight(parameters.layout()),
                quadBottom(parameters.layout()),
                shaderParams(parameters, morph, state, backgroundTask)
        );
    }

    private HudShaderParams shaderParams(
            PresenceHudVisualParameters parameters,
            float morph,
            PresenceHudVisualState state,
            boolean backgroundTask
    ) {
        int shaderState = state == PresenceHudVisualState.LISTENING
                ? PresenceHudVisualState.IDLE.ordinal()
                : state.ordinal();
        int opacityKey = 1_000;
        int intensityKey = Math.round(parameters.intensity() * 100.0F);
        int speedKey = Math.round(parameters.speed() * 100.0F);
        int convergenceKey = Math.round(parameters.convergence() * 100.0F);
        int pulseKey = Math.round(parameters.pulse() * 100.0F);
        int morphKey = Math.round(morph * 1_000.0F);
        long key = shaderState;
        key = key * 1_001L + opacityKey;
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
