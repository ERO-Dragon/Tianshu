package com.rheinmetal.tianshu.client.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderParams;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;

/**
 * Renders the Presence icon with a shader when the platform has one and a bounded Java fallback otherwise.
 * Both paths consume the same resolved visual parameters, so shader availability never changes the product state
 * model.
 *
 * <p>The fallback is drawn first and the shader is an enhancement on top: a program that is missing, failed or
 * dropped by the platform simply draws nothing, and the player still sees the icon. Nothing here knows a glyph of
 * OpenGL — fills, discs, rings and the shader quad all go through {@link HudSurface}.</p>
 */
public final class PresenceIconElementRenderer implements PresenceHudElementRenderer {
    private static final float LOADING_LAYER_OPACITY = 0.72F;
    private static final float FLUID_LAYER_OPACITY = 0.58F;

    private final PresenceHudSettings settings;
    private final PresencePartnerIconRenderer partnerRenderer = new PresencePartnerIconRenderer();
    /** One reusable quad-parameter instance: a settled frame hands the same values, so nothing is allocated. */
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

        PresenceHudLayout layout = parameters.layout();
        float time = frame.timing().updatedAtMillis() / 1_000.0F;
        float loadingOpacity = parameters.loadingBlend();
        if (loadingOpacity > 0.001F) {
            renderLoadingLayer(surface, layout, parameters, time, loadingOpacity);
        }

        float fluidOpacity = 1.0F - loadingOpacity;
        if (fluidOpacity > 0.001F) {
            PresenceHudVisualState fluidState = parameters.state() == PresenceHudVisualState.LOADING
                    ? PresenceHudVisualState.IDLE
                    : parameters.state();
            renderFluidLayer(surface, layout, parameters, fluidState, time, fluidOpacity);
        }
        if (frame.display().backgroundTask()) {
            renderBackgroundTaskMark(surface, layout, time);
        }
        // The partner marker is drawn last: it sits on top of the icon's own visual, never replaces it.
        partnerRenderer.render(surface, layout, frame.partner(),
                frame.timing().updatedAtMillis(), frame.timing().wallAtMillis());
    }

    /**
     * The parallel background-work mark: a small pulsing dot on the icon, never a different icon.
     *
     * <p>Background work runs next to whatever the player is doing, so it is drawn beside the primary visual instead
     * of replacing it. It costs three {@code fill} calls per frame and reads only the immutable frame.</p>
     */
    private static void renderBackgroundTaskMark(HudSurface surface, PresenceHudLayout layout, float time) {
        float pulse = 0.55F + 0.45F * (float) Math.sin(time * 4.0F);
        float offset = layout.sizePixels() * 0.34F;
        PresenceHudShapes.drawDisc(
                surface,
                layout.centerX() + offset,
                layout.centerY() + offset,
                Math.max(1.5F, layout.sizePixels() * 0.09F),
                0xFFEAF7FF,
                0.45F + 0.45F * pulse
        );
    }

    private void renderLoadingLayer(
            HudSurface surface,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            float time,
            float opacity
    ) {
        int color = colorFor(settings.visualPreset(), PresenceHudVisualState.LOADING, time);
        renderLoading(surface, layout, parameters, time, color, opacity * LOADING_LAYER_OPACITY);
        surface.shaderRect(
                programFor(PresenceHudVisualState.LOADING, settings.visualPreset()),
                quadLeft(layout),
                quadTop(layout),
                quadRight(layout),
                quadBottom(layout),
                shaderParams(parameters, opacity, PresenceHudVisualState.LOADING)
        );
    }

    private void renderFluidLayer(
            HudSurface surface,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            PresenceHudVisualState state,
            float time,
            float opacity
    ) {
        int color = colorFor(settings.visualPreset(), state, time);
        renderFluidCore(surface, layout, parameters, time, color, opacity * FLUID_LAYER_OPACITY);
        surface.shaderRect(
                programFor(state, settings.visualPreset()),
                quadLeft(layout),
                quadTop(layout),
                quadRight(layout),
                quadBottom(layout),
                shaderParams(parameters, opacity, state)
        );
        if (parameters.listening() || parameters.pulse() > 0.01F) {
            float wavePhase = PresenceHudShapes.fract(time * (0.34F + parameters.speed() * 0.18F));
            float waveRadius = layout.sizePixels() * (0.48F + wavePhase * 0.46F);
            float waveAlpha = (1.0F - wavePhase) * (0.20F + parameters.pulse() * 0.45F) * opacity;
            PresenceHudShapes.drawRing(surface, layout.centerX(), layout.centerY(), waveRadius,
                    Math.max(1.0F, layout.sizePixels() * 0.045F), color, waveAlpha, 0.0F, 1.0F);
        }
    }

    /**
     * The quad parameters of this frame, reused while the values stay the same.
     *
     * <p>A settled visual changes none of them, so a steady HUD allocates nothing; only the frames around a state
     * change or a cross-fade build a new instance.</p>
     */
    private HudShaderParams shaderParams(PresenceHudVisualParameters parameters, float opacity, PresenceHudVisualState state) {
        int opacityKey = Math.round(opacity * 1_000.0F);
        int key = (state.ordinal() * 1_001 + opacityKey) * 997
                + Math.round(parameters.intensity() * 100.0F) * 31
                + Math.round(parameters.speed() * 100.0F);
        if (cachedShaderParams == null || key != cachedShaderKey) {
            cachedShaderParams = new HudShaderParams(
                    parameters.intensity(),
                    parameters.speed(),
                    parameters.convergence(),
                    parameters.pulse(),
                    opacity,
                    state.ordinal()
            );
            cachedShaderKey = key;
        }
        return cachedShaderParams;
    }

    private static HudProgram programFor(PresenceHudVisualState state, PresenceHudVisualPreset preset) {
        if (state == PresenceHudVisualState.LOADING) {
            return HudProgram.LOADING;
        }
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

    private void renderLoading(
            HudSurface surface,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            float time,
            int color,
            float opacity
    ) {
        float baseRadius = Math.max(1.0F, layout.sizePixels() * 0.18F);
        float spacing = layout.sizePixels() * 0.26F;
        for (int i = 0; i < 3; i++) {
            float phase = time * 3.0F - i * 0.42F;
            float lift = (float) Math.sin(phase) * layout.sizePixels() * 0.10F;
            float alpha = 0.34F + 0.66F * (0.5F + 0.5F * (float) Math.sin(phase));
            PresenceHudShapes.drawDisc(
                    surface,
                    layout.centerX() - spacing + i * spacing,
                    layout.centerY() - lift,
                    baseRadius * (0.86F + 0.14F * alpha),
                    color,
                    alpha * parameters.intensity() * opacity
            );
        }
    }

    private void renderFluidCore(
            HudSurface surface,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            float time,
            int color,
            float opacity
    ) {
        float centerX = layout.centerX();
        float centerY = layout.centerY();
        float size = layout.sizePixels();
        float pulse = 1.0F + 0.06F * (float) Math.sin(time * (1.2F + parameters.speed()));
        float outerRadius = size * (0.42F + parameters.intensity() * 0.09F) * pulse;
        float glowRadius = size * (0.76F + parameters.intensity() * 0.14F);
        PresenceHudShapes.drawDisc(surface, centerX, centerY, glowRadius, color,
                0.10F * parameters.intensity() * opacity);
        PresenceHudShapes.drawDisc(surface, centerX, centerY, glowRadius * 0.78F, color,
                0.12F * parameters.intensity() * opacity);

        PresenceHudVisualPreset preset = settings.visualPreset();
        int lobes = preset == PresenceHudVisualPreset.PRESET_TWO ? 8 : 6;
        float orbit = size * (0.10F + (1.0F - parameters.convergence()) * 0.12F);
        for (int i = 0; i < lobes; i++) {
            float angle = (float) (i * Math.PI * 2.0 / lobes) + time * (0.18F + parameters.speed() * 0.22F);
            float wobble = 1.0F + 0.20F * (float) Math.sin(time * 1.7F + i * 1.31F);
            float lobeRadius = size * (preset == PresenceHudVisualPreset.PRESET_TWO ? 0.18F : 0.16F) * wobble;
            float x = centerX + (float) Math.cos(angle) * orbit;
            float y = centerY + (float) Math.sin(angle) * orbit;
            PresenceHudShapes.drawDisc(surface, x, y, lobeRadius, color,
                    0.26F * parameters.intensity() * opacity);
        }
        PresenceHudShapes.drawDisc(surface, centerX, centerY, outerRadius, color,
                0.66F * parameters.intensity() * opacity);
        PresenceHudShapes.drawDisc(
                surface,
                centerX,
                centerY,
                outerRadius * (0.52F + parameters.convergence() * 0.18F),
                0xFFFFFFFF,
                0.40F * parameters.intensity() * opacity
        );

        float rotation = time * (0.16F + parameters.speed() * 0.16F);
        float arcAlpha = 0.34F + 0.34F * parameters.intensity();
        PresenceHudShapes.drawRing(
                surface,
                centerX,
                centerY,
                size * 0.46F,
                Math.max(1.0F, size * 0.055F),
                color,
                arcAlpha * opacity,
                rotation,
                0.60F
        );
    }

    private static int colorFor(PresenceHudVisualPreset preset, PresenceHudVisualState state, float time) {
        float phase = PresenceHudShapes.fract(time * (preset == PresenceHudVisualPreset.PRESET_TWO ? 0.06F : 0.10F));
        int first = preset == PresenceHudVisualPreset.PRESET_TWO ? 0xFFB28CFF : 0xFF78E8FF;
        int second = preset == PresenceHudVisualPreset.PRESET_TWO ? 0xFF6A8CFF : 0xFFFF8DD8;
        float stateBias = switch (state) {
            case CHAT_THINKING -> 0.22F;
            case TASK -> 0.48F;
            case CHAT -> 0.72F;
            default -> 0.34F;
        };
        return PresenceHudShapes.mixColor(first, second, PresenceHudShapes.fract(phase + stateBias));
    }
}
