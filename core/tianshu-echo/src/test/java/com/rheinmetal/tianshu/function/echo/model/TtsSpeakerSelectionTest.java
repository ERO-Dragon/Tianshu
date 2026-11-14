package com.rheinmetal.tianshu.function.echo.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TtsSpeakerSelectionTest {

    private static TtsModelInfo modelWithLatinVoice(int latinVoice) {
        var info = new TtsModelInfo();
        info.defaultSpeakerId = 7;
        info.speakerIdByScript = java.util.Map.of("LATIN", latinVoice);
        return info;
    }

    @Test
    void latinTextSpeaksWithTheLatinVoice() {
        assertEquals(0, TtsSpeakerSelection.select(modelWithLatinVoice(0), "Hello there.", 7));
    }

    @Test
    void chineseTextKeepsTheConfiguredVoice() {
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), "欢迎来到老北京民俗展", 7));
    }

    @Test
    void chineseSentenceQuotingALatinNameKeepsTheChineseVoice() {
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), "欢迎来到 Minecraft 服务器", 7));
    }

    @Test
    void sentenceOpeningInLatinUsesTheLatinVoice() {
        assertEquals(0, TtsSpeakerSelection.select(modelWithLatinVoice(0), "Welcome to 北京", 7));
    }

    @Test
    void leadingDigitsDoNotDecideTheVoice() {
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), "3号展品在二楼", 7));
    }

    @Test
    void textWithoutLettersKeepsTheConfiguredVoice() {
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), "……！？12.5%", 7));
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), "", 7));
        assertEquals(7, TtsSpeakerSelection.select(modelWithLatinVoice(0), null, 7));
    }

    @Test
    void modelsWithoutPerScriptVoicesKeepTheConfiguredVoice() {
        assertEquals(7, TtsSpeakerSelection.select(new TtsModelInfo(), "Hello there.", 7));
        assertEquals(7, TtsSpeakerSelection.select(null, "Hello there.", 7));
    }

    @Test
    void kokoroCatalogSpeaksLatinWithAnEnglishVoiceAndChineseWithItsDefault() {
        TtsModelInfo kokoro = TtsModelInfo.findByName("kokoro-multi-lang-v1_1");
        // Voices 0-2 of Kokoro 1.1-zh are English, so a Chinese model may not default to them.
        int chineseVoice = kokoro.getDefaultSpeakerId();
        int englishVoice = TtsSpeakerSelection.select(kokoro, "Welcome to the exhibition", chineseVoice);
        assertNotEquals(chineseVoice, englishVoice);
        assertEquals(chineseVoice, TtsSpeakerSelection.select(kokoro, "欢迎来到老北京民俗展", chineseVoice));
    }
}
