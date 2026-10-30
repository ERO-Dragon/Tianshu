package com.rheinmetal.tianshu.mc.host;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientLanguageSnapshotTest {
    @Test
    void exposesOneNormalizedLanguageSnapshotToBackgroundConsumers() {
        ClientLanguageSnapshot snapshot = new ClientLanguageSnapshot();

        assertEquals("en_us", snapshot.languageCode());
        assertEquals("en_us", snapshot.languageCode());

        snapshot.update("ZH-CN");

        assertEquals("zh_cn", snapshot.languageCode());
        assertEquals("zh_cn", snapshot.languageCode());
    }

    @Test
    void blankLanguageFallsBackToTheSupportedDefault() {
        ClientLanguageSnapshot snapshot = new ClientLanguageSnapshot();

        snapshot.update("  ");

        assertEquals("en_us", snapshot.languageCode());
        assertEquals("en_us", snapshot.languageCode());
    }
}
