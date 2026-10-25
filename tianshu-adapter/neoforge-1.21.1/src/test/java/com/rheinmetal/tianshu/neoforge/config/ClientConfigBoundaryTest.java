package com.rheinmetal.tianshu.neoforge.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform config host owns kernel settings only. Module settings live with their modules, so these
 * guardrails now check two things: what stays here, and that nothing from a feature module sneaks back in.
 */
final class ClientConfigBoundaryTest {

    private static final Path CONFIG_HOST =
            Path.of("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java");
    private static final Path INTAKE_CONFIG =
            Path.of("../../tianshu-intake/src/main/java/com/rheinmetal/tianshu/function/intake/settings/IntakeConfig.java");
    private static final Path WEAVING_CONFIG =
            Path.of("../../tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/settings/WeavingConfig.java");
    private static final Path WEAVING_MODEL_SETTINGS =
            Path.of("../../tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/settings/LlmModelSettings.java");

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void debugConfigurationHasOneGlobalSourceOfTruth() throws Exception {
        String source = read(CONFIG_HOST);

        assertTrue(source.contains("DEBUG_ENABLED = builder.define(\"enabled\", false)"));
        assertTrue(source.contains("public boolean isDebugEnabled()"));
        assertTrue(source.contains("public void setDebugEnabled(boolean enabled)"));
        assertFalse(source.contains("DIAGNOSTICS_ENABLED"));
        assertFalse(source.contains("diagnosticsEnabled"));
        assertFalse(source.contains("DEBUG_PIPELINE_ENABLED"));
        assertFalse(source.contains("debugPipelineEnabled"));
    }

    @Test
    void configHostNamesNoFeatureModule() throws Exception {
        String source = read(CONFIG_HOST);

        assertFalse(source.contains("com.rheinmetal.tianshu.function."),
                "a feature module contract in the config host is what forces the kernel to follow every module");
        assertFalse(source.contains("IntakeConfiguration"));
        assertFalse(source.contains("EchoConfiguration"));
        assertFalse(source.contains("WeavingConfiguration"));
        assertFalse(source.contains("AuxiliumAssistantSettings"));
    }

    @Test
    void presenceConfigurationKeepsOnlyProductLevelHudControls() throws Exception {
        String source = read(CONFIG_HOST);

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
    void intakeDoesNotExposeTheRetiredContinuousTriggerConfiguration() throws Exception {
        String source = read(INTAKE_CONFIG);

        assertFalse(source.contains("TRIGGER_MODE"));
        assertFalse(source.contains("getTriggerMode"));
        assertFalse(source.contains("setTriggerMode"));
    }

    @Test
    void modelCredentialsKeepTheirOwnKeyInsteadOfRidingInTheModelList() throws Exception {
        String config = read(WEAVING_CONFIG);
        String codec = read(WEAVING_MODEL_SETTINGS);

        assertTrue(config.contains("KEY_CREDENTIALS"), "the credential store is its own key");
        assertTrue(config.contains("KEY_MODELS"), "the model list is its own key");
        assertTrue(codec.contains("ENTRY_TAG") && codec.contains("MODEL_TAG"));
        assertTrue(codec.contains("credentialLines()"), "credentials are serialised separately from entries");
        // Everything the chat path reads has to resolve through the active entry, otherwise the
        // runtime would keep serving whatever the single-model keys held before the list existed.
        assertTrue(config.contains("return modelSettings().activeModel().modelId();"));
        assertTrue(config.contains("return modelSettings().activeModel().baseUrl();"));
        assertTrue(config.contains("return getLlmApiKey(getLlmActiveModelId());"));
    }
}
