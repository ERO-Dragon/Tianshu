package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.MeshData;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;

/**
 * One HUD program as this Minecraft line lets the host apply it.
 *
 * <p>The renderer always asks the same question: make this program current for one quad, let the feature write the
 * program's uniforms, and submit that quad. Which types answer it is the only part of the shader pipeline that
 * changes between Minecraft lines, so it stays behind this interface and in {@link RenderPipelineHudShader}; the HUD
 * renderer only names {@code HudShader}.</p>
 *
 * <p>Since 1.21.5 a program is a {@link RenderPipeline} and its uniform values travel with the {@link RenderPass}
 * that performs the draw, so a program cannot become "current" any earlier than the draw itself. This seam therefore
 * carries two calls of one scope: {@link #apply} opens the program and hands back its writer, {@link #draw} submits
 * the quad with the values that writer holds.</p>
 */
public interface HudShader {

    /**
     * Opens the program for one quad and binds the returned sink to it.
     *
     * <p>Called on the render thread, before the feature's own binding runs. The sink is reused across quads and
     * frames: a caller must not keep it, and must not write through it after applying a different program.</p>
     *
     * @param frameTimeSeconds the surface's monotonic animation clock
     * @return the uniform sink, or {@code null} when the resources loaded right now provide no working program;
     *         in that case the caller draws nothing for this quad
     */
    HudUniformSink apply(float frameTimeSeconds);

    /**
     * Submits one quad with the uniforms written since the matching {@link #apply}, then ends that program scope.
     *
     * <p>Called once after {@link #apply} returned a sink, on the same render thread. The mesh is in the program's
     * own vertex format and already carries the GUI's transform.</p>
     */
    void draw(MeshData quad);
}
