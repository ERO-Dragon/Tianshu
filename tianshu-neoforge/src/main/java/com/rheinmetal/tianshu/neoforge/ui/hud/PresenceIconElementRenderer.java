package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.shaders.AbstractUniform;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.RenderType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Renders the Presence icon with a shader when available and a bounded Java fallback otherwise.
 * Both paths consume the same resolved visual parameters, so shader availability never changes
 * the product state model.
 */
public final class PresenceIconElementRenderer implements PresenceHudElementRenderer {
    private static final int ARC_SEGMENTS = 48;

    private final PresenceHudSettings settings;
    private final Set<ShaderInstance> unavailableShaders = Collections.newSetFromMap(new IdentityHashMap<>());

    public PresenceIconElementRenderer(PresenceHudSettings settings) {
        this.settings = settings == null
                ? PresenceHudSettings.ENABLED
                : settings;
    }

    @Override
    public PresenceHudElementType type() {
        return PresenceHudElementType.ICON;
    }

    @Override
    public void render(GuiGraphics graphics, Font font, PresenceHudElementFrame frame) {
        if (graphics == null || frame == null || frame.state() != PresenceHudElementState.ACTIVE) {
            return;
        }
        PresenceHudVisualParameters parameters = frame.visualParameters();
        if (parameters == null || parameters.layout() == null) {
            return;
        }

        PresenceHudLayout layout = parameters.layout();
        float time = frame.timing().updatedAtMillis() / 1_000.0F;
        int centerX = Math.round(layout.centerX());
        int centerY = Math.round(layout.centerY());
        float size = layout.sizePixels();
        float loadingOpacity = parameters.loadingBlend();
        if (loadingOpacity > 0.001F) {
            renderLoadingLayer(graphics, layout, parameters, time, centerX, centerY, size, loadingOpacity);
        }

        float fluidOpacity = 1.0F - loadingOpacity;
        if (fluidOpacity > 0.001F) {
            PresenceHudVisualState fluidState = parameters.state() == PresenceHudVisualState.LOADING
                    ? PresenceHudVisualState.IDLE
                    : parameters.state();
            renderFluidLayer(graphics, layout, parameters, fluidState, time, centerX, centerY, size, fluidOpacity);
        }
    }

    private void renderLoadingLayer(
            GuiGraphics graphics,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            float time,
            int centerX,
            int centerY,
            float size,
            float opacity
    ) {
        ShaderInstance shader = PresenceHudShaderRegistry.shaderFor(
                PresenceHudVisualState.LOADING,
                settings.visualPreset()
        );
        int color = colorFor(settings.visualPreset(), PresenceHudVisualState.LOADING, time);
        renderLoading(graphics, centerX, centerY, size, parameters.intensity(), time, color, opacity * 0.72F);
        if (shader != null && !unavailableShaders.contains(shader)) {
            renderShader(graphics, shader, layout, parameters, PresenceHudVisualState.LOADING, time, opacity);
        }
    }

    private void renderFluidLayer(
            GuiGraphics graphics,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            PresenceHudVisualState state,
            float time,
            int centerX,
            int centerY,
            float size,
            float opacity
    ) {
        int color = colorFor(settings.visualPreset(), state, time);
        ShaderInstance shader = PresenceHudShaderRegistry.shaderFor(state, settings.visualPreset());
        renderFluidCore(graphics, centerX, centerY, size, parameters, time, color, opacity * 0.58F);
        if (shader != null && !unavailableShaders.contains(shader)) {
            renderShader(graphics, shader, layout, parameters, state, time, opacity);
        }
        if (parameters.listening() || parameters.pulse() > 0.01F) {
            float wavePhase = fract(time * (0.34F + parameters.speed() * 0.18F));
            float waveRadius = size * (0.48F + wavePhase * 0.46F);
            float waveAlpha = (1.0F - wavePhase) * (0.20F + parameters.pulse() * 0.45F) * opacity;
            drawRing(graphics, centerX, centerY, waveRadius, Math.max(1.0F, size * 0.045F), color, waveAlpha, 0.0F, 1.0F);
        }
    }

    private boolean renderShader(
            GuiGraphics graphics,
            ShaderInstance shader,
            PresenceHudLayout layout,
            PresenceHudVisualParameters parameters,
            PresenceHudVisualState state,
            float time,
            float opacity
    ) {
        MeshData mesh = null;
        boolean depthTestDisabled = false;
        boolean depthMaskDisabled = false;
        try {
            setUniform(shader, "Time", time);
            setUniform(shader, "Intensity", parameters.intensity());
            setUniform(shader, "Speed", parameters.speed());
            setUniform(shader, "Convergence", parameters.convergence());
            setUniform(shader, "Pulse", parameters.pulse());
            setUniform(shader, "State", stateIndex(state));
            setUniform(shader, "Opacity", opacity);

            int left = Math.round(layout.centerX() - layout.sizePixels() / 2.0F);
            int top = Math.round(layout.centerY() - layout.sizePixels() / 2.0F);
            int right = Math.round(layout.centerX() + layout.sizePixels() / 2.0F);
            int bottom = Math.round(layout.centerY() + layout.sizePixels() / 2.0F);

            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(left, bottom, 0.0F).setUv(0.0F, 1.0F);
            buffer.addVertex(right, bottom, 0.0F).setUv(1.0F, 1.0F);
            buffer.addVertex(right, top, 0.0F).setUv(1.0F, 0.0F);
            buffer.addVertex(left, top, 0.0F).setUv(0.0F, 0.0F);
            mesh = buffer.build();
            if (mesh == null) {
                return false;
            }
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            depthTestDisabled = true;
            RenderSystem.depthMask(false);
            depthMaskDisabled = true;
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShader(() -> shader);
            BufferUploader.drawWithShader(mesh);
            return true;
        } catch (RuntimeException ignored) {
            unavailableShaders.add(shader);
            return false;
        } finally {
            if (depthMaskDisabled) {
                RenderSystem.depthMask(true);
            }
            if (depthTestDisabled) {
                RenderSystem.enableDepthTest();
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        AbstractUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static float stateIndex(PresenceHudVisualState state) {
        return switch (state) {
            case IDLE -> 0.0F;
            case LOADING -> 1.0F;
            case TASK -> 2.0F;
            case CHAT -> 3.0F;
            case CHAT_THINKING -> 4.0F;
        };
    }

    private void renderLoading(
            GuiGraphics graphics,
            int centerX,
            int centerY,
            float size,
            float intensity,
            float time,
            int color,
            float opacity
    ) {
        float baseRadius = Math.max(1.0F, size * 0.18F);
        float spacing = size * 0.26F;
        for (int i = 0; i < 3; i++) {
            float phase = time * 3.0F - i * 0.42F;
            float lift = (float) Math.sin(phase) * size * 0.10F;
            float alpha = 0.34F + 0.66F * (0.5F + 0.5F * (float) Math.sin(phase));
            drawDisc(
                    graphics,
                    centerX - spacing + i * spacing,
                    centerY - lift,
                    baseRadius * (0.86F + 0.14F * alpha),
                    color,
                    alpha * intensity * opacity
            );
        }
    }

    private void renderFluidCore(GuiGraphics graphics, int centerX, int centerY, float size,
                                 PresenceHudVisualParameters parameters, float time, int color, float opacity) {
        float pulse = 1.0F + 0.06F * (float) Math.sin(time * (1.2F + parameters.speed()));
        float outerRadius = size * (0.42F + parameters.intensity() * 0.09F) * pulse;
        float glowRadius = size * (0.76F + parameters.intensity() * 0.14F);
        drawDisc(graphics, centerX, centerY, glowRadius, color, 0.10F * parameters.intensity() * opacity);
        drawDisc(graphics, centerX, centerY, glowRadius * 0.78F, color, 0.12F * parameters.intensity() * opacity);

        PresenceHudVisualPreset preset = settings.visualPreset();
        int lobes = preset == PresenceHudVisualPreset.PRESET_TWO ? 8 : 6;
        float orbit = size * (0.10F + (1.0F - parameters.convergence()) * 0.12F);
        for (int i = 0; i < lobes; i++) {
            float angle = (float) (i * Math.PI * 2.0 / lobes) + time * (0.18F + parameters.speed() * 0.22F);
            float wobble = 1.0F + 0.20F * (float) Math.sin(time * 1.7F + i * 1.31F);
            float lobeRadius = size * (preset == PresenceHudVisualPreset.PRESET_TWO ? 0.18F : 0.16F) * wobble;
            float x = centerX + (float) Math.cos(angle) * orbit;
            float y = centerY + (float) Math.sin(angle) * orbit;
            drawDisc(graphics, x, y, lobeRadius, color, 0.26F * parameters.intensity() * opacity);
        }
        drawDisc(graphics, centerX, centerY, outerRadius, color, 0.66F * parameters.intensity() * opacity);
        drawDisc(
                graphics,
                centerX,
                centerY,
                outerRadius * (0.52F + parameters.convergence() * 0.18F),
                0xFFFFFFFF,
                0.40F * parameters.intensity() * opacity
        );

        float rotation = time * (0.16F + parameters.speed() * 0.16F);
        float arcAlpha = 0.34F + 0.34F * parameters.intensity();
        drawRing(
                graphics,
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
        float phase = fract(time * (preset == PresenceHudVisualPreset.PRESET_TWO ? 0.06F : 0.10F));
        int first = preset == PresenceHudVisualPreset.PRESET_TWO ? 0xFFB28CFF : 0xFF78E8FF;
        int second = preset == PresenceHudVisualPreset.PRESET_TWO ? 0xFF6A8CFF : 0xFFFF8DD8;
        float stateBias = switch (state) {
            case CHAT_THINKING -> 0.22F;
            case TASK -> 0.48F;
            case CHAT -> 0.72F;
            default -> 0.34F;
        };
        return mixColor(first, second, fract(phase + stateBias));
    }

    private static void drawDisc(GuiGraphics graphics, float centerX, float centerY, float radius,
                                 int color, float opacity) {
        int alpha = clampColor(opacity * ((color >>> 24) & 0xFF));
        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        int bands = Math.min(9, Math.max(3, (int) Math.ceil(radius)));
        for (int band = 0; band < bands; band++) {
            float normalized = ((band + 0.5F) / bands) * 2.0F - 1.0F;
            float horizontal = radius * (float) Math.sqrt(Math.max(0.0F, 1.0F - normalized * normalized));
            int y = Math.round(centerY + normalized * radius);
            int left = Math.round(centerX - horizontal);
            int right = Math.round(centerX + horizontal);
            graphics.fill(
                    RenderType.guiOverlay(),
                    left,
                    y,
                    right + 1,
                    y + Math.max(1, Math.round(sizeBand(radius, bands))),
                    argb
            );
        }
    }

    private static float sizeBand(float radius, int bands) {
        return Math.max(1.0F, radius * 2.0F / bands);
    }

    private static void drawRing(GuiGraphics graphics, int centerX, int centerY, float radius,
                                 float thickness, int color, float opacity, float rotation, float coverage) {
        int alpha = clampColor(opacity * ((color >>> 24) & 0xFF));
        int argb = (alpha << 24) | (color & 0x00FFFFFF);
        int halfThickness = Math.max(1, Math.round(thickness / 2.0F));
        int segments = Math.max(1, Math.round(ARC_SEGMENTS * Math.min(1.0F, Math.max(0.0F, coverage))));
        for (int i = 0; i < segments; i++) {
            float angle = rotation + (float) (i * Math.PI * 2.0 / ARC_SEGMENTS);
            int x = Math.round(centerX + (float) Math.cos(angle) * radius);
            int y = Math.round(centerY + (float) Math.sin(angle) * radius);
            graphics.fill(
                    RenderType.guiOverlay(),
                    x - halfThickness,
                    y - halfThickness,
                    x + halfThickness + 1,
                    y + halfThickness + 1,
                    argb
            );
        }
    }

    private static int mixColor(int first, int second, float progress) {
        int r = Math.round(((first >>> 16) & 0xFF) + (((second >>> 16) & 0xFF) - ((first >>> 16) & 0xFF)) * progress);
        int g = Math.round(((first >>> 8) & 0xFF) + (((second >>> 8) & 0xFF) - ((first >>> 8) & 0xFF)) * progress);
        int b = Math.round((first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * progress);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static float fract(float value) {
        return value - (float) Math.floor(value);
    }
}
