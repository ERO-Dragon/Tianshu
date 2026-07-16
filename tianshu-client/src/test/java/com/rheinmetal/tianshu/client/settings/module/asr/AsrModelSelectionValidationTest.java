package com.rheinmetal.tianshu.client.settings.module.asr;

import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrModelSelectionValidationTest {
    @Test
    void enabledAsrRequiresASelectedModel() {
        assertFalse(AsrSettingsRegistrySource.validateModelSelection(true, "", null, false).success());
    }

    @Test
    void enabledAsrRequiresTheSelectedModelToBeInstalled() {
        AsrModelInfo model = AsrModelManager.getAllModels().getFirst();

        assertFalse(AsrSettingsRegistrySource.validateModelSelection(true, model.localKey(), model, false).success());
    }

    @Test
    void disabledAsrMayKeepNoModelSelected() {
        assertTrue(AsrSettingsRegistrySource.validateModelSelection(false, "", null, false).success());
    }
}
