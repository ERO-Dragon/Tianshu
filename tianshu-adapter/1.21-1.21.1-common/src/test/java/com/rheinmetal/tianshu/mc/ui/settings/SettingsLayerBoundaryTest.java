package com.rheinmetal.tianshu.mc.ui.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer window owns only what is above the page.
 *
 * <p>NeoForge's GUI layer stack draws the lower layers first and the current screen last, so a layer that
 * rendered the page underneath it would paint that page's text on top of its own panel — the defect this
 * test locks down. The page's dimming and the panel are drawn here; the page itself is not.
 */
final class SettingsLayerBoundaryTest {
    private static final String HOST = "../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/ui/settings/SettingsLayerScreen.java";
    private static final String RENDERER =
            "../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/ui/settings/VanillaSettingsLayerRenderer.java";

    /**
     * The page below is drawn here only when the loader has no layer stack to draw it.
     */
    @Test
    void layerDrawsThePageBelowOnlyWhenThereIsNoLayerStack() throws Exception {
        String host = read(HOST);
        assertTrue(host.contains("if (drawParent)"), "the decision is a flag, not two render paths");
        assertTrue(methodBody(host, "public void render(GuiGraphics").contains("parent.render("),
                "the flag's branch is what draws the page");

        for (String path : List.of(RENDERER)) {
            String source = read(path);
            assertFalse(source.contains("parent.render("), () -> "page redrawn by " + path);
            assertFalse(source.contains(".renderWithTooltip("), () -> "page redrawn by " + path);
            assertFalse(source.contains("renderBackground("), () -> "page background redrawn by " + path);
        }
    }
    @Test
    void layerDrawsDimThenContentThenControls() throws Exception {
        String render = methodBody(read(HOST), "public void render(GuiGraphics");

        int refresh = render.indexOf("renderer.refresh(");
        int dim = render.indexOf("graphics.fill(");
        int content = render.indexOf("renderer.render(");
        int controls = render.indexOf("for (GuiEventListener child : children())");

        assertTrue(refresh >= 0, "the description is re-read before drawing");
        assertTrue(dim >= 0, "the page below is dimmed");
        assertTrue(content >= 0, "the panel is drawn");
        assertTrue(controls >= 0, "the controls are drawn");
        assertTrue(dim < content, "the dim goes under the panel, not over it");
        assertTrue(content < controls, "controls are drawn last, above the panel content");
        assertTrue(refresh < dim, "state is read before anything is drawn from it");
    }

    @Test
    void layerClosesThroughTheGuiLayerStackOnly() throws Exception {
        String source = read(HOST);

        assertTrue(source.contains("super.onClose()"), "closing pops the layer instead of replacing screens");
        assertFalse(source.contains("setScreen("), "a layer never swaps the screen itself");
        assertTrue(source.contains("requestRebuildCurrentPage()"), "the page is rebuilt after the layer goes");
        assertTrue(source.contains("layer.onClosed().run()"), "the description's close hook runs");
    }

    @Test
    void closeHookRunsExactlyOnce() throws Exception {
        String finish = methodBody(read(HOST), "private void finishLayer()");

        assertTrue(finish.contains("if (closed)"), "a second close is ignored");
        assertTrue(finish.indexOf("closed = true") < finish.indexOf("layer.onClosed().run()"),
                "the guard is raised before the hook can run again");
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
