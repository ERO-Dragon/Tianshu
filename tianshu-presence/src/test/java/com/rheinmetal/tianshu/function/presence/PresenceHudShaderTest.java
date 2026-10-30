package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudProgram;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudShaderParams;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shader assets and the uniform contract belong to presence, and presence declares them.
 *
 * <p>Two properties matter: the files live in presence's own namespace (the platform ships no visual for a feature),
 * and the declarations map each module-owned program id to that namespace. Moving or renaming a file without the
 * declaration — or the other way round — would make the icon silently invisible, so both are checked here. The binder
 * test keeps the parameter record and the shader's uniform names from drifting apart.</p>
 */
final class PresenceHudShaderTest {
    private static final Path SHADER_DIR =
            Path.of("src/main/resources/assets/tianshu_presence/shaders/core");

    @Test
    void everyProgramIsDeclaredWithAPresenceAsset() {
        HudShaderCatalog.clear();
        try {
            PresenceHudShaders.declare();
            Map<HudProgramId, String> declared = HudShaderCatalog.declarations();

            assertEquals(2, declared.size());
            assertEquals(PresenceHudShaders.SHADER_PRESET_ONE,
                    declared.get(PresenceHudProgram.PRESET_ONE.id()));
            assertEquals(PresenceHudShaders.SHADER_PRESET_TWO,
                    declared.get(PresenceHudProgram.PRESET_TWO.id()));
        } finally {
            HudShaderCatalog.clear();
        }
    }

    @Test
    void eachProgramBindsTheUniformsItsShaderDeclares() {
        PresenceHudShaderParams params =
                new PresenceHudShaderParams(0.5F, 0.6F, 0.7F, 0.2F, 1.0F, 0.4F, 3, 1.0F);

        for (PresenceHudProgram program : PresenceHudProgram.values()) {
            RecordingSink sink = new RecordingSink();
            program.bind(sink, params);

            assertEquals(Set.of(
                    "Time", "Intensity", "Speed", "Convergence", "Pulse", "Opacity", "Morph", "State", "Task"
            ), sink.names, program.id().toString());
        }
    }

    @Test
    void theDeclaredAssetsExistAndBlendWithAlpha() throws Exception {
        for (String shader : new String[]{
                "presence_preset_one.json",
                "presence_preset_two.json"
        }) {
            Path path = SHADER_DIR.resolve(shader);
            assertTrue(Files.isRegularFile(path), "declared shader missing: " + path);
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertTrue(source.contains("\"srcrgb\": \"srcalpha\""), shader);
            assertTrue(source.contains("\"dstrgb\": \"1-srcalpha\""), shader);
            for (String uniform : new String[]{
                    "Time", "Intensity", "Speed", "Convergence", "Pulse", "Opacity", "Morph", "State", "Task"
            }) {
                assertTrue(source.contains("\"name\": \"" + uniform + "\""),
                        shader + " must declare the shared uniform " + uniform);
            }
            assertTrue(source.contains("\"vertex\": \"tianshu_presence:presence_icon\""),
                    "the shared vertex stage must be presence's own: " + shader);
            assertFalse(source.contains("tianshu:hud_"), "no kernel-namespaced shader may be referenced");
        }
    }

    private static final class RecordingSink implements HudUniformSink {
        private final Set<String> names = new LinkedHashSet<>();

        @Override
        public float frameTimeSeconds() {
            return 1.25F;
        }

        @Override
        public boolean setFloat(String name, float value) {
            names.add(name);
            return true;
        }

        @Override
        public boolean setInt(String name, int value) {
            names.add(name);
            return true;
        }

        @Override
        public boolean setVec2(String name, float x, float y) {
            names.add(name);
            return true;
        }

        @Override
        public boolean setVec3(String name, float x, float y, float z) {
            names.add(name);
            return true;
        }

        @Override
        public boolean setVec4(String name, float x, float y, float z, float w) {
            names.add(name);
            return true;
        }
    }
}
