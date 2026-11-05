package com.rheinmetal.tianshu.mc.compat;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD shader seam, as 1.21.9 can express it.
 *
 * <p>1.21.6 kept the {@link com.mojang.blaze3d.pipeline.RenderPipeline} program model but changed the uniform model:
 * a pipeline describes std140 uniform blocks, and a {@link com.mojang.blaze3d.systems.RenderPass} is given buffers,
 * not individual values. A HUD program's shaders declare plain uniform variables — the asset contract they share with
 * the 1.21.4 and 1.21.5 adapters — so the seam keeps the descriptor as the single source of a program's uniforms and
 * writes those locations directly. This test locks what the port must keep so that a feature's declared program still
 * reaches the screen: the renderer opens the program, lets the feature bind, and then submits the quad; the pipeline
 * carries the fixed-function draw state while the declared uniform values are written to the applied program for every
 * quad; and no old-model shader type survives as a fallback that would silently stop drawing the program.</p>
 *
 * <p>Two parts of that contract changed with the generations. Up to 1.21.5 the engine itself filled the plain
 * {@code ProjMat} and {@code ModelViewMat} variables of a GUI program; 1.21.6 moved the GUI transform into std140
 * blocks and no longer does, so the sink now seeds those two matrices itself instead of leaving the descriptor's
 * identity defaults in place: the projection is the engine's GUI volume (depth 1000..11000) and the model-view pushes
 * the quad to that volume's far plane (-11000), where a quad whose vertices carry GUI pixels at z = 0 has to sit.
 * 1.21.9 then deleted the state manager's remaining scalar, vector and matrix uniform writers — only the sampler
 * write, the location lookup and the program bind are left — so the values are uploaded through LWJGL's GL20 entry
 * points, the same GL calls the removed helpers made, while the locations still resolve through
 * {@code GlStateManager._glGetUniformLocation}. The tests below therefore pin both the defaults the feature never
 * writes, the two values the platform supplies, and the transport this generation actually has.</p>
 */
final class HudShaderSeamBoundaryTest {
    private static final String COMMON = "../1.21.9-1.21.10-common/src/main/java/com/rheinmetal/tianshu/mc";
    private static final String RENDERER = COMMON + "/ui/hud/HudSurfaceRenderer.java";
    private static final String SHADER = COMMON + "/compat/RenderPipelineHudShader.java";
    private static final String SINK = COMMON + "/compat/ShaderUniformSink.java";
    private static final String DESCRIPTOR = COMMON + "/compat/HudProgramDescriptor.java";
    private static final String PRESENCE = "../../tianshu-presence/src/main/resources/assets/tianshu_presence";

    @Test
    void rendererOpensBindsAndSubmitsTheQuad() throws Exception {
        String render = methodBody(read(RENDERER), "public <P> void shaderRect(");

        int apply = render.indexOf("shader.apply(");
        int bind = render.indexOf("program.bind(");
        int draw = render.indexOf("shader.draw(");
        assertTrue(apply >= 0, "the renderer opens the feature's program");
        assertTrue(bind >= 0, "the feature writes its uniforms through the sink");
        assertTrue(draw >= 0, "the renderer submits the quad after the uniforms are written");
        assertTrue(apply < bind, "the program is opened before the feature binds");
        assertTrue(bind < draw, "the quad is drawn after the feature binds");

        for (String state : List.of("enableBlend(", "depthMask(", "disableDepthTest(", "BufferUploader")) {
            assertFalse(render.contains(state), () -> "the renderer still switches " + state);
        }
    }

    @Test
    void pipelineCarriesTheDrawStateAndTheDescriptorCarriesTheUniforms() throws Exception {
        String shader = read(SHADER);

        assertTrue(shader.contains(".withBlend(BlendFunction.TRANSLUCENT)"), "the HUD quad is alpha-blended");
        assertTrue(shader.contains(".withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)"),
                "the quad draws over the GUI without a depth buffer");
        assertTrue(shader.contains(".withVertexFormat(DefaultVertexFormat.POSITION_TEX"),
                "the pipeline's vertex format is the quad the surface builds");
        assertTrue(shader.contains("uniforms.describe(descriptor)"),
                "the program's uniform set comes from the feature's descriptor, not from the platform");
        assertFalse(shader.contains(".withUniform("),
                "1.21.6 pipelines only declare std140 blocks, which the feature's individual uniforms are not");
        assertTrue(shader.contains("uniforms.applyTo(program.getProgramId())"),
                "every declared uniform is written to the applied program for the quad");
        assertTrue(shader.contains("GlStateManager._glUseProgram(program.getProgramId())"),
                "the program is current before its uniforms are written through GL20");

        for (String featureSpecific : List.of("presence", "\"Time\"", "Intensity", "ModelViewMat")) {
            assertFalse(shader.contains(featureSpecific), () -> "the platform learns " + featureSpecific);
        }
    }

    /**
     * The sink starts every quad from the descriptor's defaults, and from the two values the engine used to supply.
     *
     * <p>This test used to assert only the first half. That was the whole contract while the engine filled
     * {@code ProjMat} and {@code ModelViewMat} for a GUI program; since 1.21.6 it does not, so the seed now also puts
     * the engine's GUI projection and the model-view that pushes the quad to its far plane into a declared 4x4 matrix
     * of either name. The assertion that a uniform the feature does not write keeps its declared default is unchanged —
     * a program that declares neither name still behaves exactly as before.</p>
     */
    @Test
    void sinkSeedsTheDeclaredDefaultsAndTheEngineGuiMatrices() throws Exception {
        String sink = read(SINK);
        String begin = methodBody(sink, "void begin(");
        String seed = methodBody(sink, "void seedGuiMatrices(");
        String applyTo = methodBody(sink, "void applyTo(");

        assertTrue(begin.contains("slot.reset()"),
                "a uniform the feature does not write keeps its declared default");
        assertTrue(begin.indexOf("slot.reset()") < begin.indexOf("seedGuiMatrices()"),
                "the engine-supplied GUI matrices replace those defaults before the feature binds");
        assertTrue(seed.contains("GUI_PROJECTION_UNIFORM") && seed.contains("GUI_POSE_UNIFORM"),
                "the two engine-supplied GUI matrix names are the ones seeded");
        assertTrue(seed.contains("guiProjection("),
                "the projection is the GUI orthographic volume, not the descriptor's identity");
        assertTrue(seed.contains("guiPose()"),
                "the pose pushes the quad onto the volume's far plane instead of leaving the descriptor's identity");
        assertTrue(seed.contains("getGuiScaledWidth()") && seed.contains("getGuiScaledHeight()"),
                "the volume follows the client window's scaled GUI");
        assertTrue(applyTo.contains("for (Slot slot : slots)"),
                "the whole declared set is written, not only the names the feature happened to change");
        // 1.21.9 deleted GlStateManager's scalar, vector and matrix uniform writers together with its uniform
        // objects, so the values now go through LWJGL's GL20 entry points; the location lookup still uses the engine.
        assertTrue(applyTo.contains("GL20.glUniform"),
                "the values reach the applied program through the GL entry points this generation still has");
        assertFalse(applyTo.contains("_glUniform1("),
                "the engine helper 1.21.9 deleted is not what uploads them");
        assertFalse(applyTo.contains("_glUniformMatrix4("),
                "the engine helper 1.21.9 deleted is not what uploads them");
        assertTrue(sink.contains("GlStateManager._glGetUniformLocation"),
                "locations still resolve through the engine, which is what links the feature's plain uniforms");
        assertTrue(sink.contains("slot.matches(intStorage, width)"),
                "a write whose width does not match the declared type is rejected, not uploaded");
    }

    /**
     * The two matrices together, not just the projection: the engine's GUI volume is the 1000..11000 depth range and
     * its model-view translates GUI geometry by -11000. The volume's x/y mapping is GUI pixels to clip space; its z
     * mapping only contains a quad that the model-view has pushed to the far plane.
     */
    @Test
    void guiMatricesMapTheScaledGuiOntoClipSpaceAtTheEngineGuiDepth() {
        int width = 320;
        int height = 240;
        Matrix4f projection = ShaderUniformSink.guiProjection(width, height);
        Matrix4f pose = ShaderUniformSink.guiPose();

        assertNdc(projection, pose, 0.0F, 0.0F, -1.0F, 1.0F, "the GUI's top-left pixel");
        assertNdc(projection, pose, width, height, 1.0F, -1.0F, "the GUI's bottom-right pixel");
        assertNdc(projection, pose, width / 2.0F, height / 2.0F, 0.0F, 0.0F, "the GUI's centre");

        float posedZ = ndcZ(projection, pose);
        assertEquals(1.0F, posedZ, 1.0E-4F,
                "the posed quad sits on the far plane of the GUI volume, inside the clip range");
        assertTrue(posedZ >= -1.0F && posedZ <= 1.0F,
                "the posed quad is clipped by neither the near nor the far plane");
        assertEquals(-1.2F, ndcZ(projection, new Matrix4f()), 1.0E-4F,
                "without the pose a z = 0 quad lands behind the near plane, which is why the model-view is not the identity");
    }

    private static void assertNdc(
            Matrix4f projection,
            Matrix4f pose,
            float guiX,
            float guiY,
            float expectedX,
            float expectedY,
            String point
    ) {
        Vector4f clip = new Vector4f(guiX, guiY, 0.0F, 1.0F).mul(pose).mul(projection);
        assertEquals(expectedX, clip.x / clip.w, 1.0E-6F, point + " maps to the wrong clip x");
        assertEquals(expectedY, clip.y / clip.w, 1.0E-6F, point + " maps to the wrong clip y");
    }

    /** The clip-space z of a quad vertex at GUI z = 0, after the model-view and the projection. */
    private static float ndcZ(Matrix4f projection, Matrix4f pose) {
        Vector4f clip = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F).mul(pose).mul(projection);
        return clip.z / clip.w;
    }

    @Test
    void declaredAssetsAreWhereThisGenerationResolvesThem() throws Exception {
        String presentOne = read(PRESENCE + "/shaders/core/presence_preset_one.json");
        assertTrue(presentOne.contains("\"vertex\": \"tianshu_presence:presence_icon\""),
                "the descriptor names its vertex source without a folder");
        for (String asset : List.of("presence_icon.vsh", "presence_preset_one.fsh", "presence_preset_one.json")) {
            assertTrue(Files.exists(Path.of(PRESENCE + "/shaders/core/" + asset)),
                    () -> "missing program asset " + asset);
        }
        assertTrue(read(DESCRIPTOR).contains("SHADER_FOLDER + \"/\" + declared.getPath()"),
                "a bare shader source is resolved inside the descriptor's folder");
    }

    @Test
    void noOldShaderModelTypeSurvivesInTheSharedLayer() throws Exception {
        List<String> removed = List.of(
                "net.minecraft.client.renderer.CompiledShaderProgram",
                "net.minecraft.client.renderer.ShaderProgram",
                "com.mojang.blaze3d.vertex.BufferUploader",
                "com.mojang.blaze3d.shaders.AbstractUniform",
                "RenderSystem.setShader(",
                // 1.21.9 deleted the state manager's plain-uniform writers; the sink uses GL20 instead. The
                // sampler write is deliberately absent from this list: it still exists and is not a uniform value.
                "GlStateManager._glUniform1(",
                "GlStateManager._glUniform2(",
                "GlStateManager._glUniform3(",
                "GlStateManager._glUniform4(",
                "GlStateManager._glUniformMatrix4("
        );
        Path root = Path.of(COMMON);
        try (Stream<Path> sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String type : removed) {
                    assertFalse(text.contains(type), () -> source + " still names " + type);
                }
            }
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Missing method: " + signature);
        }
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            }
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
