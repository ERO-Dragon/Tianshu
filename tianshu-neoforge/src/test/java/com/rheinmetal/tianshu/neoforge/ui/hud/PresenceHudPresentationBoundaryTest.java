package com.rheinmetal.tianshu.neoforge.ui.hud;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PresenceHudPresentationBoundaryTest {
    @Test
    void positionEditorUsesATransparentCanvasWithoutVanillaBlur() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/PresenceHudPositionEditorScreen.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("renderTransparentBackground(graphics)"));
        assertFalse(source.contains("renderBackground(graphics"));
    }

    @Test
    void positionEditorDoesNotRenderVanillaScreenAfterItsCanvas() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/PresenceHudPositionEditorScreen.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("super.render(graphics"));
        assertTrue(source.contains("for (Renderable renderable : renderables)"));
    }

    @Test
    void presenceSettingsExposePositionOnlyThroughTheEditor() throws Exception {
        String source = Files.readString(
                Path.of("../tianshu-client/src/main/java/com/rheinmetal/tianshu/client/settings/module/presence/PresenceSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("presence.hud.edit_position"));
        assertFalse(source.contains("presence.hud.position_x"));
        assertFalse(source.contains("presence.hud.position_y"));
    }

    @Test
    void shaderPathOwnsGuiDepthAndColorState() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/PresenceIconElementRenderer.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("RenderSystem.disableDepthTest()"));
        assertTrue(source.contains("RenderSystem.depthMask(false)"));
        assertTrue(source.contains("RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)"));
        assertTrue(source.contains("renderFluidCore(graphics"));
        assertTrue(source.contains("renderLoading(graphics"));
        assertTrue(source.contains("RenderType.guiOverlay()"));
    }

    @Test
    void everyPresenceShaderDeclaresAlphaBlending() throws Exception {
        for (String shader : new String[]{
                "presence_loading.json",
                "presence_preset_one.json",
                "presence_preset_two.json"
        }) {
            String source = Files.readString(
                    Path.of("src/main/resources/assets/tianshu/shaders/core/" + shader),
                    StandardCharsets.UTF_8
            );
            assertTrue(source.contains("\"srcrgb\": \"srcalpha\""), shader);
            assertTrue(source.contains("\"dstrgb\": \"1-srcalpha\""), shader);
        }
    }

    @Test
    void shaderRegistrationUsesExplicitNamespacedLocations() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/bootstrap/NeoForgeClientBootstrap.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("ResourceLocation.fromNamespaceAndPath(TianshuNeoForge.MOD_ID, path)"));
        assertTrue(source.contains("new ShaderInstance(event.getResourceProvider(), shaderLocation"));
        assertFalse(source.contains("new ShaderInstance(event.getResourceProvider(), name"));
    }

    @Test
    void asrVadOverlayUsesHistoricalThresholdCurves() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/ui/hud/AsrVadDebugOverlay.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("snapshot.startThresholds()"));
        assertTrue(source.contains("snapshot.stopThresholds()"));
        assertTrue(source.contains("drawThresholdCurve"));
        assertFalse(source.contains("drawThreshold(graphics, snapshot.startThreshold()"));
    }
}
