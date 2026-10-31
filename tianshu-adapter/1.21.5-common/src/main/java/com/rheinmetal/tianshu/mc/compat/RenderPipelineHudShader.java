package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The 1.21.5 shader pipeline: a program is a {@link RenderPipeline} — two shader sources, the uniforms it exposes and
 * the fixed-function state of its draw — and applying it means drawing one quad through a {@link RenderPass}.
 *
 * <p>The pipeline is converted from the feature's own program descriptor (see {@link HudProgramDescriptor}) the first
 * time the program is used after a resource reload. That is the asset the game itself read up to 1.21.4, so a feature
 * still owns its shader sources and its uniform names; the platform only translates the declaration into this
 * generation's type.</p>
 *
 * <p>Three consequences of the new model are deliberate, and are trades this class makes on the HUD's behalf:</p>
 * <ul>
 *   <li>The descriptor's {@code blend} block is not converted. On 1.21.5 a pipeline's blend is one source/destination
 *       factor pair with no blend equation, and every HUD quad is alpha-blended over the GUI — which is the blend the
 *       HUD programs declare. A program that asked for something else would be drawing with {@link BlendFunction#TRANSLUCENT}
 *       here instead.</li>
 *   <li>The descriptor's {@code samplers} are not converted: a HUD program draws an untextured quad above the GUI, so
 *       it has nothing to sample.</li>
 *   <li>The descriptor's uniform defaults are honoured rather than dropped, because a 1.21.5 pass has to carry a value
 *       for every uniform its pipeline declares, including the ones a feature does not write this frame.</li>
 * </ul>
 *
 * <p>The pipeline belongs to the current resource-load generation. It is built lazily on the render thread, and the
 * platform re-registers the programs after every reload, so a handle never outlives the packs its shader sources came
 * from.</p>
 */
public final class RenderPipelineHudShader implements HudShader {

    private final ResourceLocation declaredAsset;
    private final LogSink logSink;
    private final ShaderUniformSink uniforms = new ShaderUniformSink();

    private RenderPipeline pipeline;
    private boolean resolved;

    public RenderPipelineHudShader(ResourceLocation declaredAsset, LogSink logSink) {
        this.declaredAsset = Objects.requireNonNull(declaredAsset, "declaredAsset");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    @Override
    public HudUniformSink apply(float frameTimeSeconds) {
        if (pipeline() == null) {
            return null;
        }
        uniforms.begin(frameTimeSeconds);
        return uniforms;
    }

    @Override
    public void draw(MeshData quad) {
        RenderPipeline current = pipeline;
        if (current == null || quad == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft == null ? null : minecraft.getMainRenderTarget();
        if (target == null || target.getColorTexture() == null) {
            return;
        }
        MeshData.DrawState state = quad.drawState();
        GpuBuffer vertices = state.format().uploadImmediateVertexBuffer(quad.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.mode());
        GpuBuffer indexBuffer = indices.getBuffer(state.indexCount());
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(target.getColorTexture(), OptionalInt.empty())) {
            pass.setPipeline(current);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indexBuffer, indices.type());
            uniforms.applyTo(pass);
            pass.drawIndexed(0, state.indexCount());
        }
    }

    /**
     * The pipeline for the loaded packs, built on first use.
     *
     * <p>A descriptor that cannot be read is reported once and leaves the program without a pipeline: the surface
     * then draws nothing for it, which is the same outcome as an unreadable shader had on earlier lines.</p>
     */
    private RenderPipeline pipeline() {
        if (!resolved) {
            resolved = true;
            pipeline = build();
        }
        return pipeline;
    }

    private RenderPipeline build() {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resources = minecraft == null ? null : minecraft.getResourceManager();
        HudProgramDescriptor descriptor = resources == null
                ? null
                : HudProgramDescriptor.read(resources, declaredAsset);
        if (descriptor == null) {
            logSink.warn("platform.hud.shader_descriptor_unavailable id=" + declaredAsset);
            return null;
        }
        uniforms.describe(descriptor);
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        declaredAsset.getNamespace(),
                        "hud/" + declaredAsset.getPath()
                ))
                .withVertexShader(descriptor.vertexShader())
                .withFragmentShader(descriptor.fragmentShader())
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS);
        for (HudProgramDescriptor.Uniform uniform : descriptor.uniforms()) {
            builder = builder.withUniform(uniform.name(), uniform.type());
        }
        return builder.build();
    }
}
