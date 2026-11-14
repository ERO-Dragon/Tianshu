package com.rheinmetal.tianshu.function.presence.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug pipeline section is gated by the global debug switch — and gated twice.
 *
 * <p>A section that is only "disabled" still draws its contents: the renderer greys the border and deactivates the
 * widgets, but the rows stay on screen. A release build has the switch permanently off, so passing the switch as
 * {@code enabled} alone would leave a live pipeline readout visible in a shipping build. The section therefore has
 * to pass it as {@code visible} as well, which takes it off the page entirely.</p>
 */
final class PresenceDebugSectionVisibilityTest {

    private static final Path REGISTRY = Path.of(
            "src/main/java/com/rheinmetal/tianshu/function/presence/settings/PresenceSettingsRegistrySource.java");

    @Test
    void theDebugSectionIsHiddenAndNotMerelyDisabled() throws Exception {
        String source = Files.readString(REGISTRY, StandardCharsets.UTF_8);

        assertTrue(source.contains(
                        "list(\"presence.debug.pipeline\", presence(\"section.debug_pipeline\"), debugEnabled, debugEnabled,"),
                "the debug switch must decide both 'enabled' and 'visible', otherwise release builds still show the rows");
    }
}
