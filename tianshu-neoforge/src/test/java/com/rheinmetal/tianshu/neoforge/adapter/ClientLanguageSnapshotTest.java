package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientLanguageSnapshotTest {
    @Test
    void exposesOneNormalizedLanguageSnapshotToBackgroundConsumers() {
        ClientLanguageSnapshot snapshot = new ClientLanguageSnapshot();

        assertEquals("en_us", snapshot.languageCode());
        assertEquals(AXPromptLanguage.EN_US, snapshot.promptLanguage());

        snapshot.update("ZH-CN");

        assertEquals("zh_cn", snapshot.languageCode());
        assertEquals(AXPromptLanguage.ZH_CN, snapshot.promptLanguage());
    }

    @Test
    void blankLanguageFallsBackToTheSupportedDefault() {
        ClientLanguageSnapshot snapshot = new ClientLanguageSnapshot();

        snapshot.update("  ");

        assertEquals("en_us", snapshot.languageCode());
        assertEquals(AXPromptLanguage.EN_US, snapshot.promptLanguage());
    }
}
