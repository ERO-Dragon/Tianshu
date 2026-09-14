package com.rheinmetal.tianshu.neoforge.ui.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TianshuSettingsScreenBoundaryTest {
    @Test
    void everyScreenOwnsFreshSettingsSessions() throws Exception {
        String module = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsModule.java");
        String createScreen = methodBody(module, "public Screen createScreen()");

        assertTrue(createScreen.contains("new SettingsSessionRegistry(logs)"));
        assertTrue(createScreen.contains("new SettingsCoordinator("));
        assertTrue(createScreen.contains("new GlobalDebugSettingsSession(globalDebugSettings)"));
        assertTrue(createScreen.contains("sessions.register(debugSession)"));
        assertFalse(module.contains("private final SettingsCoordinator coordinator"));
    }

    @Test
    void globalDebugToggleLivesInTheFixedBottomActionArea() throws Exception {
        String screen = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsScreen.java");
        String actions = methodBody(screen, "private void addBottomActions");

        assertTrue(actions.contains("debugSession.toggle()"));
        assertTrue(actions.contains("debugLabel()"));
        assertTrue(actions.contains("width - margin - debugWidth"));
        assertFalse(actions.contains("diagnosticsEnabled"));
    }

    @Test
    void asynchronousRefreshReturnsThroughMinecraftSchedulerAndCoalesces() throws Exception {
        String screen = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsScreen.java");
        String refresh = methodBody(screen, "public void requestRebuildCurrentPage()");

        assertTrue(refresh.contains("if (rebuildQueued)"));
        assertTrue(refresh.contains("Minecraft.getInstance().execute("));
        assertTrue(refresh.contains("Minecraft.getInstance().screen == this"));
        assertFalse(refresh.contains(".join()"));
        assertFalse(refresh.contains("Thread.sleep"));
    }

    @Test
    void selectionPanelConsumesBackgroundKeyboardInput() throws Exception {
        String screen = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsScreen.java");
        String keyPressed = methodBody(screen, "public boolean keyPressed");
        String charTyped = methodBody(screen, "public boolean charTyped");

        assertTrue(keyPressed.contains("if (selectionPanel != null)"));
        assertTrue(keyPressed.contains("return true"));
        assertTrue(charTyped.contains("if (selectionPanel != null)"));
        assertTrue(charTyped.contains("return true"));
    }

    @Test
    void selectionPanelAndNestedScrollRegionsOwnPointerInput() throws Exception {
        String screen = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsScreen.java");
        String mouseClicked = methodBody(screen, "public boolean mouseClicked");
        String mouseScrolled = methodBody(screen, "public boolean mouseScrolled");

        assertTrue(mouseClicked.contains("selectionPanel != null && selectionPanel.mouseClicked"));
        assertTrue(mouseScrolled.contains("if (selectionPanel != null)"));
        assertTrue(mouseScrolled.contains("rightPanelScrollRegions"));
        assertTrue(mouseScrolled.indexOf("rightPanelScrollRegions") < mouseScrolled.indexOf("rightPanelScroll.canScroll()"));
        assertTrue(mouseScrolled.contains("rebuildCurrentPage()"));
    }

    @Test
    void screenAndRendererDoNotPerformBlockingOrFileWork() throws Exception {
        String screen = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/TianshuSettingsScreen.java");
        String renderer = read("src/main/java/com/rheinmetal/tianshu/neoforge/ui/settings/VanillaModuleSettingsRenderer.java");

        for (String source : java.util.List.of(screen, renderer)) {
            assertFalse(source.contains("Files."));
            assertFalse(source.contains("Thread.sleep"));
            assertFalse(source.contains(".join()"));
        }
        assertTrue(screen.contains("catch (RuntimeException exception)"));
        assertTrue(screen.contains("tianshu.gui.settings.status.module_unavailable"));
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
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
