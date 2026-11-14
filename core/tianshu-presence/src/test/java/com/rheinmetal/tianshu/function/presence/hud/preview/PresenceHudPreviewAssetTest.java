package com.rheinmetal.tianshu.function.presence.hud.preview;

import com.rheinmetal.tianshu.function.presence.hud.HudPreviewAssetExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the web HUD preview honest: its model values and shader copies must be the ones the mod builds.
 *
 * <p>The page is a development tool, but a stale tool is worse than no tool — it would show a HUD the game no longer
 * draws. Regenerating is one command, and this test makes forgetting it a build failure instead of a silent lie.</p>
 */
final class PresenceHudPreviewAssetTest {

    @Test
    void thePreviewPageMirrorsTheHudModel() throws Exception {
        List<String> problems = HudPreviewAssetExporter.verify(Path.of("tools", "hud-preview", "index.html"));
        assertTrue(problems.isEmpty(), () -> "HUD preview assets are stale; run "
                + ":tianshu-presence:exportHudPreviewAssets\n" + String.join("\n", problems));
    }
}
