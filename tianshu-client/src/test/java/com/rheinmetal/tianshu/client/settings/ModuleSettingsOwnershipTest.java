package com.rheinmetal.tianshu.client.settings;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class ModuleSettingsOwnershipTest {
    @Test
    void internalIrAndIaModulesDoNotOwnVisibleSettingsCategories() {
        assertFalse(Files.exists(SettingsSourcePaths.page("client", "ir/IrSettingsRegistrySource.java")));
        assertFalse(Files.exists(SettingsSourcePaths.page("client", "ia/IaSettingsRegistrySource.java")));
        assertFalse(Files.exists(SettingsSourcePaths.page("client", "ModuleDiagnosticsSettingsRegistrySource.java")));
    }

    @Test
    void productModuleSettingsDoNotContainDiagnosticsControls() throws Exception {
        for (String relativePath : List.of(
                "intake/IntakeSettingsRegistrySource.java",
                "auxilium/AuxiliumSettingsRegistrySource.java",
                "weaving/WeavingSettingsRegistrySource.java",
                "echo/EchoSettingsRegistrySource.java",
                "presence/PresenceSettingsRegistrySource.java"
        )) {
            String source = Files.readString(
                    SettingsSourcePaths.page(relativePath),
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
                SettingsSourcePaths.page("intake", "IntakeSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("asr.vad"));
        assertFalse(source.contains("option.vad"));
        assertFalse(source.contains("vadEnabled"));
    }
}
