package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The 26.3 shader pipeline: a program is a {@link RenderPipeline} — two shader sources and the fixed-function state of
 * its draw — and applying it means drawing one quad through a {@link RenderPass}.
 *
 * <p>The pipeline is converted from the feature's own program descriptor (see {@link HudProgramDescriptor}) the first
 * time the program is used after a resource reload. That is the asset the game itself read up to 1.21.4, so a feature
 * still owns its shader sources and its uniform names; the platform only translates the declaration into this
 * generation's type.</p>
 *
 * <p>Three consequences of the new model are deliberate, and are trades this class makes on the HUD's behalf:</p>
 * <ul>
 *   <li>The descriptor's {@code blend} block is not converted. A pipeline's blend is one source/destination factor
 *       pair with no blend equation, and every HUD quad is alpha-blended over the GUI — which is the blend the HUD
 *       programs declare. A program that asked for something else would be drawing with
 *       {@link BlendFunction#TRANSLUCENT} here instead.</li>
 *   <li>The descriptor's {@code samplers} are not converted: a HUD program draws an untextured quad above the GUI, so
 *       it has nothing to sample.</li>
 *   <li>The descriptor's uniform defaults are honoured rather than dropped: a pipeline carries no individual
 *       uniforms, so the program's locations are written directly and every one of them starts each quad from the
 *       default the feature declared.</li>
 * </ul>
 *
 * <p>26.3 compiles a pipeline asynchronously: {@code GpuDevice} hands back a future of a
 * {@link CompiledRenderPipeline.Pending}, and the GL link happens when
 * {@link CompiledRenderPipeline.Pending#finishCompile()} is called. The compile is therefore started where the
 * descriptor is converted (once per program and resource generation) on the game's background executor, and the render
 * thread finishes it on the first frame that finds the future complete. A frame that arrives while the compile is still
 * running draws nothing for that program: a HUD effect appearing one or two frames later is cosmetic, while blocking the
 * render thread on a shader link is not. The linked pipeline is then handed to the pass directly, which is what this
 * generation's {@link RenderPass#setPipeline(CompiledRenderPipeline)} takes — the game's pipeline cache is not involved,
 * so the platform never mutates engine-wide state. A compile that fails is reported once and the program is skipped
 * from then on, without a per-frame diagnostic.</p>
 *
 * <p>The pipeline belongs to the current resource-load generation. It is built lazily on the render thread, and the
 * platform re-registers the programs after every reload, so a handle never outlives the packs its shader sources came
 * from.</p>
 */
public final class RenderPipelineHudShader implements HudShader {

    private final Identifier declaredAsset;
    private final LogSink logSink;
    private final ShaderUniformSink uniforms = new ShaderUniformSink();

    private RenderPipeline pipeline;
    private HudShaderSource shaderSource;
    private CompletableFuture<CompiledRenderPipeline.Pending> compilation;
    private CompiledRenderPipeline compiledPipeline;
    private boolean resolved;
    private boolean failed;

    public RenderPipelineHudShader(Identifier declaredAsset, LogSink logSink) {
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
        if (pipeline == null || quad == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();
        if (target == null || target.getColorTexture() == null) {
            return;
        }
        if (compiledPipeline == null && !resolveCompiledPipeline()) {
            // Still compiling, or this program can never be drawn: either way this frame has no quad to submit.
            return;
        }
        GlProgram program = programOf(compiledPipeline);
        if (program == null) {
            fail("compiled pipeline has no GL program", null);
            return;
        }
        MeshData.DrawState state = quad.drawState();
        // 26.2 removed the vertex format's immediate-upload helper: the quad's bytes become a transient vertex buffer,
        // and the indices still come from the engine's sequential buffer for the topology.
        GpuBuffer vertices = RenderSystem.getDevice()
                .createBuffer(() -> "tianshu hud quad", GpuBuffer.USAGE_VERTEX, quad.vertexBuffer());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.primitiveTopology());
        GpuBuffer indexBuffer = indices.getBuffer(state.indexCount());
        try (vertices; RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "tianshu hud", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(compiledPipeline);
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(indexBuffer, indices.type());
            // The pass carries no individual-uniform setter, so the program is bound here and the work a pass used to
            // do for the HUD — carrying the feature's values — is done through the GL state directly.
            GlStateManager._glUseProgram(program.getProgramId());
            uniforms.applyTo(program.getProgramId());
            pass.drawIndexed(state.indexCount(), 1, 0, 0, 0);
        }
    }

    /** The GL program behind a compiled pipeline, or {@code null} when it has no OpenGL representation. */
    private static GlProgram programOf(CompiledRenderPipeline compiled) {
        if (!(compiled instanceof FrontendRenderPipeline frontend)) {
            return null;
        }
        return frontend.backendRenderPipeline() instanceof GlRenderPipeline gl ? gl.program() : null;
    }

    /**
     * Moves a finished compile into a linked pipeline.
     *
     * <p>The only place the future is observed. Finishing the compile links the GL program, so it has to happen on the
     * render thread — which is exactly where a draw runs; nothing here blocks, because an incomplete future is simply
     * reported as "no program this frame".</p>
     *
     * @return true when a linked pipeline is now available, false while the compile is pending or after it failed
     */
    private boolean resolveCompiledPipeline() {
        if (compilation == null || failed) {
            return false;
        }
        if (compilation.isCompletedExceptionally()) {
            // The future is already done, so the exception is available without waiting; the diagnostic keeps the
            // compiler's own message.
            fail("compile failed", compilation.exceptionNow());
            return false;
        }
        if (!compilation.isDone()) {
            return false;
        }
        CompiledRenderPipeline.Pending pending = compilation.getNow(null);
        if (pending == null || pending == CompiledRenderPipeline.Pending.NULL) {
            fail("compile produced no pending pipeline", null);
            return false;
        }
        CompiledRenderPipeline linked;
        try {
            linked = pending.finishCompile();
        } catch (RuntimeException linkFailure) {
            fail("link failed", linkFailure);
            return false;
        }
        if (linked == null) {
            fail("link produced no pipeline", null);
            return false;
        }
        compiledPipeline = linked;
        // The source stays alive with the handle: the engine's own caches close a shader source together with the
        // pipelines compiled from it, and a backend recompile needs it again. It is dropped when the platform
        // re-registers the programs after a reload.
        return true;
    }

    /** Reports a program that can never be drawn, once: the caller then skips it without a per-frame diagnostic. */
    private void fail(String reason, Throwable failure) {
        if (failed) {
            return;
        }
        failed = true;
        closeShaderSource();
        if (failure == null) {
            logSink.warn("platform.hud.shader_pipeline_failed id=" + declaredAsset + " reason=" + reason);
        } else {
            logSink.error("platform.hud.shader_pipeline_failed id=" + declaredAsset + " reason=" + reason, failure);
        }
    }

    private void closeShaderSource() {
        if (shaderSource == null) {
            return;
        }
        shaderSource.close();
        shaderSource = null;
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
        RenderPipeline built = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(
                        declaredAsset.getNamespace(),
                        "hud/" + declaredAsset.getPath()
                ))
                .withVertexShader(descriptor.vertexShader())
                .withFragmentShader(descriptor.fragmentShader())
                // 26.1 describes the draw state as two independent states instead of a blend plus a depth test:
                // the colour target carries the blend (and write mask), the depth-stencil state carries the test.
                // The HUD quad is alpha-blended over the GUI and must not consult a depth buffer, which an empty
                // depth-stencil state expresses (it is also what makes the pass depth-less, as before).
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                // 26.2 split the old (format, mode) pair into a vertex binding slot and a primitive topology.
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
        shaderSource = HudShaderSource.of(resources);
        try {
            compilation = RenderSystem.getDevice().compilePipeline(built, shaderSource, Util.backgroundExecutor());
        } catch (RuntimeException startFailure) {
            fail("compile could not be started", startFailure);
        }
        return built;
    }
}
