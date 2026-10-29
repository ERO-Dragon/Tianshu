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
    private static final Path PRESENCE_CONFIG =
            Path.of("../../tianshu-client/src/main/java/com/rheinmetal/tianshu/client/presence/PresenceModuleConfig.java");
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
    void presenceSettingsLiveWithPresenceAndNotInTheKernelConfig() throws Exception {
        String source = read(CONFIG_HOST);

        assertFalse(source.contains("PRESENCE_"), "a feature's keys in the kernel config make the kernel follow it");
        assertFalse(source.contains("PresenceHudSettings"));
        assertFalse(source.contains("presence"), "even a comment ties the two modules together");

        String presence = read(PRESENCE_CONFIG);
        assertTrue(presence.contains("\"hud.enabled\""));
        assertTrue(presence.contains("\"hud.status_text.enabled\""));
        assertTrue(presence.contains("\"hud.icon.enabled\""));
        assertTrue(presence.contains("\"hud.icon.size\""));
        assertTrue(presence.contains("\"hud.icon.preset\""));
        assertTrue(presence.contains("\"hud.icon.x\""));
        assertTrue(presence.contains("\"hud.icon.y\""));
        // Presence's own settings are the HUD's product decisions, never another module's status readout.
        assertFalse(presence.contains("ASR_STATUS_VISIBLE"));
        assertFalse(presence.contains("LLM_STATUS_VISIBLE"));
        assertFalse(presence.contains("TTS_STATUS_VISIBLE"));
        assertFalse(presence.contains("AX_STATUS_VISIBLE"));
        assertFalse(presence.contains("PANEL_"));
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
