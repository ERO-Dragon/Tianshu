package com.rheinmetal.tianshu.client.presence.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PresenceHudVisualPresetTest {
    @Test
    void exposesOnlyTheTwoApprovedNonLoadingPresets() {
        assertEquals(2, PresenceHudVisualPreset.values().length);
        assertNotNull(PresenceHudVisualPreset.PRESET_ONE);
        assertNotNull(PresenceHudVisualPreset.PRESET_TWO);
    }

    @Test
    void defaultHudSettingsUsePresetOne() {
        assertEquals(PresenceHudVisualPreset.PRESET_ONE, PresenceHudSettings.ENABLED.visualPreset());
    }
}
