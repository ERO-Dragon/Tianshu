package com.rheinmetal.tianshu.neoforge.ui.hud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.rheinmetal.tianshu.mc.ui.hud.HudAnchorEditorScreen;
import com.rheinmetal.tianshu.mc.ui.hud.HudSurfaceRenderer;

/**
 * Guardrails for the split between the platform's drawing and the feature's presentation.
 *
 * <p>They read source files because the properties they protect are structural: which side owns render state, which
 * side owns a screen, and that no feature ever reaches for OpenGL directly.</p>
 */
final class PresenceHudPresentationBoundaryTest {
    private static final Path ANCHOR_SCREEN =
            Path.of("../common/src/main/java/com/rheinmetal/tianshu/mc/ui/hud/HudAnchorEditorScreen.java");
    private static final Path SURFACE =
            Path.of("../common/src/main/java/com/rheinmetal/tianshu/mc/ui/hud/HudSurfaceRenderer.java");
    private static final Path PRESENCE_ICON =
            Path.of("../../tianshu-presence/src/main/java/com/rheinmetal/tianshu/function/presence/hud/PresenceIconElementRenderer.java");

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void anchorEditorUsesATransparentCanvasWithoutVanillaBlur() throws Exception {
        String source = read(ANCHOR_SCREEN);

        assertTrue(source.contains("renderTransparentBackground(graphics)"));
        assertFalse(source.contains("renderBackground(graphics"));
    }

    @Test
    void anchorEditorDoesNotRenderVanillaScreenAfterItsCanvas() throws Exception {
        String source = read(ANCHOR_SCREEN);

        assertFalse(source.contains("super.render(graphics"));
        assertTrue(source.contains("for (GuiEventListener child : children())"));
        assertTrue(source.contains("view.resolve("), "the preview must come from the feature's resolver");
    }

    @Test
    void presenceSettingsExposePositionOnlyThroughTheEditor() throws Exception {
        String source = read(
                Path.of("../../tianshu-presence/src/main/java/com/rheinmetal/tianshu/function/presence/settings/PresenceSettingsRegistrySource.java")
        );

        assertTrue(source.contains("presence.hud.edit_position"));
        assertFalse(source.contains("presence.hud.position_x"));
        assertFalse(source.contains("presence.hud.position_y"));
        assertTrue(source.contains("HudAnchorEditor"), "the editor is a platform port, not a module screen");
    }

    @Test
    void theSurfaceOwnsGuiDepthAndColorState() throws Exception {
        String surface = read(SURFACE);

        assertTrue(surface.contains("RenderSystem.disableDepthTest()"));
        assertTrue(surface.contains("RenderSystem.depthMask(false)"));
        assertTrue(surface.contains("RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)"));
        assertTrue(surface.contains("BufferUploader.drawWithShader(mesh)"));
        assertFalse(surface.contains("presence"), "the platform's surface names no feature");
    }

    @Test
    void theSurfaceCallsTheFeatureBinderWithoutNamingUniforms() throws Exception {
        String surface = read(SURFACE);

        assertTrue(surface.contains("program.bind(uniforms, params)"),
                "the feature owns the uniform binding, not the platform");
        assertFalse(surface.contains("\"Intensity\""), "the platform must not hard-code a feature uniform");
        assertFalse(surface.contains("\"Morph\""), "the platform must not hard-code a feature uniform");
    }

    @Test
    void theFeatureDrawsOnlyThroughTheSurface() throws Exception {
        String icon = read(PRESENCE_ICON);

        assertTrue(icon.contains("surface.shaderRect("));
        assertFalse(icon.contains("renderFluidCore"));
        assertFalse(icon.contains("renderLoading"));
        assertFalse(icon.contains("PresenceHudShapes"), "the icon's shapes live in the shader, not in Java");
        assertFalse(icon.contains("RenderSystem"));
        assertFalse(icon.contains("net.minecraft"), "a feature composes the HUD, the platform produces pixels");
    }

    @Test
    void shaderProgramsComeFromFeatureDeclarationsNotFromTheKernel() throws Exception {
        String source = read(Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"));

        assertTrue(source.contains("HudShaderCatalog.declarations()"),
                "the platform registers what features declared, instead of naming their files");
        assertTrue(source.contains("ResourceLocation.tryParse(shaderId)"),
                "a declared id is a namespaced resource location, not a path under the kernel namespace");
        assertTrue(source.contains("new ShaderInstance(event.getResourceProvider(), shaderLocation"));
        assertFalse(source.contains("fromNamespaceAndPath"), "the kernel must not build a feature's asset path");
        assertFalse(source.contains("hud_loading"), "no shader file name is hard-coded in the platform");
    }

    @Test
    void vadDebugOverlayAndRecordingKeyAreRetired() throws Exception {
        String events = read(Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/event/NeoForgeClientEvents.java"));
        String bootstrap = read(Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"));

        assertFalse(events.contains("AsrVadDebugOverlay"));
        assertFalse(events.contains("vadRecordKey"));
        assertFalse(events.contains("tickVadRecording"));
        assertFalse(bootstrap.contains("vadRecordKey"));
        assertFalse(bootstrap.contains("key.tianshu.vad_record"));
    }
}
