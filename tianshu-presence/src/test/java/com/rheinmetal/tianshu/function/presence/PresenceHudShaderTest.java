package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shader assets belong to presence, and presence declares them.
 *
 * <p>Two properties matter: the files live in presence's own namespace (the platform ships no visual for a feature),
 * and the declarations map each api family to that namespace. Moving or renaming a file without the declaration —
 * or the other way round — would make the icon silently invisible, so both are checked here.</p>
 */
final class PresenceHudShaderTest {
    private static final Path SHADER_DIR =
            Path.of("src/main/resources/assets/tianshu_presence/shaders/core");

    @Test
    void everyProgramFamilyIsDeclaredWithPresenceAssets() {
        HudShaderCatalog.clear();
        try {
            PresenceHudShaders.declare();
            Map<HudProgram, String> declared = HudShaderCatalog.declarations();

            assertEquals(3, declared.size());
            assertEquals("tianshu_presence:presence_loading", declared.get(HudProgram.LOADING));
            assertEquals("tianshu_presence:presence_preset_one", declared.get(HudProgram.PRESET_ONE));
            assertEquals("tianshu_presence:presence_preset_two", declared.get(HudProgram.PRESET_TWO));
        } finally {
            HudShaderCatalog.clear();
        }
    }

    @Test
    void theDeclaredAssetsExistAndBlendWithAlpha() throws Exception {
        for (String shader : new String[]{
                "presence_loading.json",
                "presence_preset_one.json",
                "presence_preset_two.json"
        }) {
            Path path = SHADER_DIR.resolve(shader);
            assertTrue(Files.isRegularFile(path), "declared shader missing: " + path);
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertTrue(source.contains("\"srcrgb\": \"srcalpha\""), shader);
            assertTrue(source.contains("\"dstrgb\": \"1-srcalpha\""), shader);
            assertTrue(source.contains("\"vertex\": \"tianshu_presence:presence_icon\""),
                    "the shared vertex stage must be presence's own: " + shader);
            assertFalse(source.contains("tianshu:hud_"), "no kernel-namespaced shader may be referenced");
        }
    }
}
