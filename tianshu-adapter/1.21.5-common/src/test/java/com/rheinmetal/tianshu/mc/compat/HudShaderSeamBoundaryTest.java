package com.rheinmetal.tianshu.mc.compat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD shader seam, as 1.21.5 can express it.
 *
 * <p>1.21.5 removed the compiled-program model: a program is now a {@link com.mojang.blaze3d.pipeline.RenderPipeline}
 * and its uniforms are uploaded by the {@link com.mojang.blaze3d.systems.RenderPass} that performs the draw. This test
 * locks the three properties the port must keep so that a feature's declared program still reaches the screen: the
 * renderer opens the program, lets the feature bind, and then submits the quad; every uniform the feature's descriptor
 * declares is declared on the pipeline and uploaded with the quad; and no old-model shader type survives as a
 * fallback that would silently stop drawing the program.</p>
 */
final class HudShaderSeamBoundaryTest {
    private static final String COMMON = "../1.21.5-common/src/main/java/com/rheinmetal/tianshu/mc";
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
    void pipelineCarriesTheDrawStateAndEveryDeclaredUniform() throws Exception {
        String shader = read(SHADER);

        assertTrue(shader.contains(".withBlend(BlendFunction.TRANSLUCENT)"), "the HUD quad is alpha-blended");
        assertTrue(shader.contains(".withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)"),
                "the quad draws over the GUI without a depth buffer");
        assertTrue(shader.contains(".withVertexFormat(DefaultVertexFormat.POSITION_TEX"),
                "the pipeline's vertex format is the quad the surface builds");
        int declare = shader.indexOf("for (HudProgramDescriptor.Uniform uniform : descriptor.uniforms())");
        assertTrue(declare >= 0, "the pipeline's uniforms come from the feature's descriptor");
        assertTrue(shader.contains(".withUniform(uniform.name(), uniform.type())"),
                "every declared uniform is declared on the pipeline");

        for (String featureSpecific : List.of("presence", "\"Time\"", "Intensity", "ModelViewMat")) {
            assertFalse(shader.contains(featureSpecific), () -> "the platform learns " + featureSpecific);
        }
    }

    @Test
    void sinkSeedsTheDeclaredDefaultsAndUploadsTheWholeSet() throws Exception {
        String sink = read(SINK);
        String begin = methodBody(sink, "void begin(");
        String applyTo = methodBody(sink, "void applyTo(");

        assertTrue(begin.contains("slot.reset()"),
                "a uniform the feature does not write keeps its declared default");
        assertTrue(applyTo.contains("pass.setUniform(slot.name()"),
                "the pass carries every declared uniform, which is what a 1.21.5 draw requires");
        assertTrue(sink.contains("slot.matches(intStorage, width)"),
                "a write whose width does not match the declared type is rejected, not uploaded");
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
                "RenderSystem.setShader("
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
