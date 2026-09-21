package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class ModuleSettingsOwnershipTest {
    @Test
    void internalIrAndIaModulesDoNotOwnVisibleSettingsCategories() {
        Path root = Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module");

        assertFalse(Files.exists(root.resolve("ir/IrSettingsRegistrySource.java")));
        assertFalse(Files.exists(root.resolve("ia/IaSettingsRegistrySource.java")));
        assertFalse(Files.exists(root.resolve("ModuleDiagnosticsSettingsRegistrySource.java")));
    }

    @Test
    void productModuleSettingsDoNotContainDiagnosticsControls() throws Exception {
        for (String relativePath : List.of(
                "asr/AsrSettingsRegistrySource.java",
                "ax/AXSettingsRegistrySource.java",
                "llm/LlmSettingsRegistrySource.java",
                "tts/TtsSettingsRegistrySource.java",
                "presence/PresenceSettingsRegistrySource.java"
        )) {
            String source = Files.readString(
                    Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module").resolve(relativePath),
                    StandardCharsets.UTF_8
            );
            assertFalse(source.contains("diagnosticsEnabled"), relativePath);
            assertFalse(source.contains("diagnostics.enabled"), relativePath);
            assertFalse(source.contains("debugPipelineEnabled"), relativePath);
        }
    }

    @Test
    void asrSettingsDoNotExposeAnInternalVadSwitch() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module/asr/AsrSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("asr.vad"));
        assertFalse(source.contains("option.vad"));
        assertFalse(source.contains("vadEnabled"));
    }
}
