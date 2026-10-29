package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.rheinmetal.tianshu.api.client.host.hud.HudFrameContext;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderParams;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one implementation of {@link HudSurface}: it turns the feature's drawing calls into Minecraft drawing.
 *
 * <p>Every piece of render state the platform owns lives here and nowhere else — the depth test, the shader colour,
 * the pose stack and the shader program. A feature call can therefore never leave the GUI in a broken
 * state, and a program that fails at runtime is remembered once instead of being retried every frame.</p>
 *
 * <p>The instance is bound to one frame at a time and reused; nothing here may be retained by a caller. Text widths and
 * texture lookups are cached because both are per-frame questions with stable answers.</p>
 */
final class NeoForgeHudSurface implements HudSurface {
    private final Map<String, Integer> textWidths = new HashMap<>();
    private final Map<String, ResourceLocation> textures = new HashMap<>();
    private final Set<String> missingTextures = new java.util.HashSet<>();
    private final Set<ShaderInstance> unavailablePrograms =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private GuiGraphics graphics;
    private Font font;
    private HudFrameContext frame;

    void bind(GuiGraphics graphics, Font font, HudFrameContext frame) {
        this.graphics = graphics;
        this.font = font;
        this.frame = frame;
    }

    /** Resource and language reloads change both caches' answers, so the platform clears them together. */
    void resetCaches() {
        textWidths.clear();
        textures.clear();
        missingTextures.clear();
    }

    @Override
    public int width() {
        return frame == null ? 1 : frame.width();
    }

    @Override
    public int height() {
        return frame == null ? 1 : frame.height();
    }

    @Override
    public int textWidth(String text) {
        if (text == null || text.isEmpty() || font == null) {
            return 0;
        }
        Integer cached = textWidths.get(text);
        if (cached != null) {
            return cached;
        }
        int width = font.width(text);
        textWidths.put(text, width);
        return width;
    }

    @Override
    public int lineHeight() {
        return font == null ? 9 : font.lineHeight;
    }

    @Override
    public void fill(float left, float top, float right, float bottom, int argb) {
        if (graphics == null) {
            return;
        }
        graphics.fill(
                RenderType.guiOverlay(),
                Math.round(left),
                Math.round(top),
                Math.round(right),
                Math.round(bottom),
                argb
        );
    }

    @Override
    public void text(String text, float x, float y, int argb, boolean shadow) {
        if (graphics == null || font == null || text == null || text.isBlank()) {
            return;
        }
        graphics.drawString(font, text, Math.round(x), Math.round(y), argb, shadow);
    }

    @Override
    public void blit(
            String textureId,
            float centreX,
            float centreY,
            float width,
            float height,
            float rotationRadians,
            float scale,
            float alpha
    ) {
        if (graphics == null || alpha <= 0.01F) {
            return;
        }
        ResourceLocation location = resolve(textureId);
        if (location == null) {
            return;
        }
        int drawWidth = Math.max(1, Math.round(width));
        int drawHeight = Math.max(1, Math.round(height));
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(centreX, centreY, 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotation(rotationRadians));
            graphics.pose().scale(Math.max(0.01F, scale), Math.max(0.01F, scale), 1.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, Math.min(1.0F, alpha));
            graphics.blit(
                    location,
                    -drawWidth / 2,
                    -drawHeight / 2,
                    0.0F,
                    0.0F,
                    drawWidth,
                    drawHeight,
                    drawWidth,
                    drawHeight
            );
        } catch (RuntimeException missing) {
            missingTextures.add(textureId);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.pose().popPose();
        }
    }

    @Override
    public void shaderRect(
            HudProgram program,
            float left,
            float top,
            float right,
            float bottom,
            HudShaderParams params
    ) {
        ShaderInstance shader = HudShaderPrograms.get(program);
        if (shader == null || params == null || unavailablePrograms.contains(shader)) {
            return;
        }
        MeshData mesh = null;
        boolean depthTestDisabled = false;
        boolean depthMaskDisabled = false;
        try {
            float time = (frame == null ? 0L : frame.renderMillis()) / 1_000.0F;
            setUniform(shader, "Time", time);
            setUniform(shader, "Intensity", params.intensity());
            setUniform(shader, "Speed", params.speed());
            setUniform(shader, "Convergence", params.convergence());
            setUniform(shader, "Pulse", params.pulse());
            setUniform(shader, "State", params.state());
            setUniform(shader, "Task", params.task());
            setUniform(shader, "Opacity", params.opacity());

            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(left, bottom, 0.0F).setUv(0.0F, 1.0F);
            buffer.addVertex(right, bottom, 0.0F).setUv(1.0F, 1.0F);
            buffer.addVertex(right, top, 0.0F).setUv(1.0F, 0.0F);
            buffer.addVertex(left, top, 0.0F).setUv(0.0F, 0.0F);
            mesh = buffer.build();
            if (mesh == null) {
                return;
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
        } catch (RuntimeException failure) {
            // One bad program must not cost a frame every tick: remember it and skip that program for the rest of the session.
            unavailablePrograms.add(shader);
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

    private ResourceLocation resolve(String textureId) {
        if (textureId == null || textureId.isBlank() || missingTextures.contains(textureId)) {
            return null;
        }
        ResourceLocation cached = textures.get(textureId);
        if (cached != null) {
            return cached;
        }
        ResourceLocation location = ResourceLocation.tryParse(textureId);
        if (location == null) {
            missingTextures.add(textureId);
            return null;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null
                    || minecraft.getResourceManager() == null
                    || minecraft.getResourceManager().getResource(location).isEmpty()) {
                missingTextures.add(textureId);
                return null;
            }
        } catch (RuntimeException unavailable) {
            missingTextures.add(textureId);
            return null;
        }
        textures.put(textureId, location);
        return location;
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        AbstractUniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }
}
