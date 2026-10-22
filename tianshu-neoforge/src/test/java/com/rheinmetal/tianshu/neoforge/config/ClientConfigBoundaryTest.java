package com.rheinmetal.tianshu.neoforge.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientConfigBoundaryTest {
    @Test
    void debugConfigurationHasOneGlobalSourceOfTruth() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("DEBUG_ENABLED = builder.define(\"enabled\", false)"));
        assertTrue(source.contains("public boolean isDebugEnabled()"));
        assertTrue(source.contains("public void setDebugEnabled(boolean enabled)"));
        assertFalse(source.contains("DIAGNOSTICS_ENABLED"));
        assertFalse(source.contains("diagnosticsEnabled"));
        assertFalse(source.contains("DEBUG_PIPELINE_ENABLED"));
        assertFalse(source.contains("debugPipelineEnabled"));
        assertFalse(source.contains("IrSettingsAccess"));
        assertFalse(source.contains("IaSettingsAccess"));
    }

    @Test
    void presenceConfigurationKeepsOnlyProductLevelHudControls() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("PRESENCE_HUD_ENABLED"));
        assertTrue(source.contains("PRESENCE_STATUS_TEXT_ENABLED"));
        assertTrue(source.contains("PRESENCE_ICON_ENABLED"));
        assertTrue(source.contains("PRESENCE_ICON_SIZE_PIXELS"));
        assertTrue(source.contains("PRESENCE_ICON_POSITION_X"));
        assertTrue(source.contains("PRESENCE_ICON_POSITION_Y"));
        assertFalse(source.contains("PRESENCE_ASR_STATUS_VISIBLE"));
        assertFalse(source.contains("PRESENCE_LLM_STATUS_VISIBLE"));
        assertFalse(source.contains("PRESENCE_TTS_STATUS_VISIBLE"));
        assertFalse(source.contains("PRESENCE_AX_STATUS_VISIBLE"));
        // The two-column usage panel lives in the settings page, so no HUD geometry may come back
        // here: a resident overlay is the presence module's job, not a configurable Tianshu surface.
        assertFalse(source.contains("PRESENCE_PANEL_"));
    }

    @Test
    void asrDoesNotExposeTheRetiredContinuousTriggerConfiguration() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("TRIGGER_MODE"));
        assertFalse(source.contains("getTriggerMode"));
        assertFalse(source.contains("setTriggerMode"));
    }

    @Test
    void modelCredentialsKeepTheirOwnKeyInsteadOfRidingInTheModelList() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains(".defineListAllowEmpty(\"models\""), "the model list is its own key");
        assertTrue(source.contains(".defineListAllowEmpty(\"credentials\""), "the credential store is its own key");
        assertTrue(source.contains("private LlmModelSettings llmModelSettings()"));
        // Everything the chat path reads has to resolve through the active entry, otherwise the
        // runtime would keep serving whatever the single-model keys held before the list existed.
        assertTrue(source.contains("return llmModelSettings().activeModel().modelId();"));
        assertTrue(source.contains("return llmModelSettings().activeModel().baseUrl();"));
        assertTrue(source.contains("return getLlmApiKey(getLlmActiveModelId());"));
    }
}
