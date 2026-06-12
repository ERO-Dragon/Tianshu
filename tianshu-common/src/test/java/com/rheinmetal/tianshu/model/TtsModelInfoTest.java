package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelInfoTest {
    @Test
    void catalogLoadsCurrentTtsModelResource() {
        TtsModelInfo.invalidateCatalogCache();

        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();

        assertFalse(catalog.isEmpty());
        assertNotNull(find(catalog, "MOSS-TTS-Nano-100M-ONNX"));
        assertNotNull(find(catalog, "ZipVoice-int8"));
        assertNotNull(find(catalog, "kokoro-multi-lang-v1_1"));
    }

    @Test
    void voiceCloneSupportIsLimitedToMossAndZipVoiceByDefault() {
        TtsModelInfo.invalidateCatalogCache();
        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();

        assertTrue(find(catalog, "MOSS-TTS-Nano-100M-ONNX").supportsVoiceClone());
        assertTrue(find(catalog, "ZipVoice-int8").supportsVoiceClone());
        assertFalse(find(catalog, "kokoro-multi-lang-v1_1").supportsVoiceClone());
        assertFalse(find(catalog, "vits-melo-tts-zh_en").supportsVoiceClone());
    }

    @Test
    void ttsCatalogUsesTenPointScores() {
        TtsModelInfo.invalidateCatalogCache();
        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();

        for (TtsModelInfo info : catalog) {
            assertTrue(info.getSynthesisQualityScore() >= 1 && info.getSynthesisQualityScore() <= 10);
            assertTrue(info.getPerformanceScore() >= 1 && info.getPerformanceScore() <= 10);
            assertTrue(info.getRecommendationScore() >= 1 && info.getRecommendationScore() <= 10);
        }
    }

    private static TtsModelInfo find(List<TtsModelInfo> catalog, String name) {
        return catalog.stream()
                .filter(info -> info != null && name.equals(info.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TTS model not found: " + name));
    }
}
