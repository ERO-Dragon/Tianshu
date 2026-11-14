package com.rheinmetal.tianshu.function.intake.settings;

import com.rheinmetal.tianshu.function.intake.model.AsrModelInfo;
import com.rheinmetal.tianshu.function.intake.model.AsrModelManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrModelSelectionValidationTest {
    @Test
    void enabledAsrRequiresASelectedModel() {
        assertFalse(IntakeSettingsRegistrySource.validateModelSelection(true, "", null, false).success());
    }

    @Test
    void enabledAsrRequiresTheSelectedModelToBeInstalled() {
        AsrModelInfo model = AsrModelManager.getAllModels().getFirst();

        assertFalse(IntakeSettingsRegistrySource.validateModelSelection(true, model.localKey(), model, false).success());
    }

    @Test
    void disabledAsrMayKeepNoModelSelected() {
        assertTrue(IntakeSettingsRegistrySource.validateModelSelection(false, "", null, false).success());
    }
}
