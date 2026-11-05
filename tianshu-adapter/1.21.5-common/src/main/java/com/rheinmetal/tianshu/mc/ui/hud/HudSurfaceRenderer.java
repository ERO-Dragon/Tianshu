package com.rheinmetal.tianshu.mc.ui.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.rheinmetal.tianshu.api.client.host.hud.HudFrameContext;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import com.rheinmetal.tianshu.mc.compat.HudShader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one implementation of {@link HudSurface}: it turns the feature's drawing calls into Minecraft drawing.
 *
 * <p>Every piece of render state the platform owns lives here and nowhere else — the shader colour, the pose stack
 * and the one frame's mesh for a shader quad. A feature call can therefore never leave the GUI in a broken state, and
 * a program that fails at runtime is remembered once instead of being retried every frame. The state a program's draw
 * needs — its blending, its depth test, its uniforms — belongs to the program's pipeline and is declared there, not
 * switched on and off around the call.</p>
 *
 * <p>The instance is bound to one frame at a time and reused; nothing here may be retained by a caller. Text widths and
 * texture lookups are cached because both are per-frame questions with stable answers.</p>
 */
public final class HudSurfaceRenderer implements HudSurface {
    /**
     * Measured widths are keyed by the text itself, so a HUD layer that renders changing text (coordinates,
     * timers, counters) would otherwise grow this map for the whole session. The cap is far above what a
     * screenful of HUD text needs; eviction costs one re-measure, never a wrong width.
     */
    private static final int TEXT_WIDTH_CACHE_CAPACITY = 256;

    private final Map<String, Integer> textWidths = new LinkedHashMap<>(TEXT_WIDTH_CACHE_CAPACITY, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > TEXT_WIDTH_CACHE_CAPACITY;
        }
    };
    private final Map<String, ResourceLocation> textures = new HashMap<>();
    private final Set<String> missingTextures = new java.util.HashSet<>();
    private final Set<HudShader> unavailablePrograms =
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
                    RenderType::guiTextured,
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
    public <P> void shaderRect(
            HudProgram<P> program,
            float left,
            float top,
            float right,
            float bottom,
            P params
    ) {
        if (program == null || params == null) {
            return;
        }
        HudShader shader = HudShaderPrograms.get(program.id());
        if (shader == null || unavailablePrograms.contains(shader)) {
            return;
        }
        MeshData quad = null;
        try {
            float time = (frame == null ? 0L : frame.renderMillis()) / 1_000.0F;
            // The program is opened here, not when it was registered: a pipeline is built from the packs loaded
            // right now, and the platform re-registers every program after a reload.
            HudUniformSink shaderUniforms = shader.apply(time);
            if (shaderUniforms == null) {
                unavailablePrograms.add(shader);
                return;
            }
            program.bind(shaderUniforms, params);

            Matrix4f pose = graphics.pose().last().pose();
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(pose, left, bottom, 0.0F).setUv(0.0F, 1.0F);
            buffer.addVertex(pose, right, bottom, 0.0F).setUv(1.0F, 1.0F);
            buffer.addVertex(pose, right, top, 0.0F).setUv(1.0F, 0.0F);
            buffer.addVertex(pose, left, top, 0.0F).setUv(0.0F, 0.0F);
            quad = buffer.build();
            if (quad == null) {
                return;
            }
            shader.draw(quad);
        } catch (RuntimeException failure) {
            // One bad program must not cost a frame every tick: remember it and skip that program for the rest of the session.
            unavailablePrograms.add(shader);
        } finally {
            if (quad != null) {
                quad.close();
            }
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
}
