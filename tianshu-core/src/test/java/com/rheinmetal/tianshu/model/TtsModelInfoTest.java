package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void catalogUsesDownloadUriWithoutLegacyDownloadUrlField() throws Exception {
        TtsModelInfo.invalidateCatalogCache();
        TtsModelInfo zipVoice = find(TtsModelInfo.loadCatalog(), "ZipVoice-int8");

        String value = (String) TtsModelInfo.class.getField("downloadUri").get(zipVoice);

        assertTrue(URI.create(value).isAbsolute());
        assertThrows(NoSuchFieldException.class, () -> TtsModelInfo.class.getField("downloadUrl"));
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

    @Test
    void modelDirectoryRequiresLoadCriticalFiles(@TempDir Path tempDir) throws Exception {
        TtsModelInfo info = find(TtsModelInfo.loadCatalog(), "vits-melo-tts-zh_en");

        Files.createFile(tempDir.resolve("model.onnx"));

        assertFalse(TtsModelInfo.isModelDirectoryComplete(info, tempDir));

        Files.createFile(tempDir.resolve("tokens.txt"));
        Files.createFile(tempDir.resolve("lexicon.txt"));
        Files.createFile(tempDir.resolve("date.fst"));
        Files.createFile(tempDir.resolve("new_heteronym.fst"));
        Files.createFile(tempDir.resolve("number.fst"));
        Files.createFile(tempDir.resolve("phone.fst"));

        assertTrue(TtsModelInfo.isModelDirectoryComplete(info, tempDir));
    }

    @Test
    void zipVoiceDirectoryRequiresEncoderDecoderVocoderAndTokens(@TempDir Path tempDir) throws Exception {
        TtsModelInfo info = find(TtsModelInfo.loadCatalog(), "ZipVoice-int8");

        Files.createFile(tempDir.resolve("tokens.txt"));
        Files.createFile(tempDir.resolve("text_encoder_int8.onnx"));
        Files.createFile(tempDir.resolve("fm_decoder_int8.onnx"));

        assertFalse(TtsModelInfo.isModelDirectoryComplete(info, tempDir));

        Files.createFile(tempDir.resolve("vocos_24khz.onnx"));
        Files.createFile(tempDir.resolve("pinyin.raw"));
        Files.createDirectory(tempDir.resolve("espeak-ng-data"));

        assertTrue(TtsModelInfo.isModelDirectoryComplete(info, tempDir));
    }

    @Test
    void mossDirectoryRequiresManifestReferencedMetaFiles(@TempDir Path tempDir) throws Exception {
        TtsModelInfo info = find(TtsModelInfo.loadCatalog(), "MOSS-TTS-Nano-100M-ONNX");

        Files.writeString(tempDir.resolve("browser_poc_manifest.json"), """
                {
                  "model_files": {
                    "tts_meta": "tts_meta.json",
                    "codec_meta": "codec_meta.json"
                  }
                }
                """);

        assertFalse(TtsModelInfo.isModelDirectoryComplete(info, tempDir));

        Files.writeString(tempDir.resolve("tts_meta.json"), "{}");
        Files.writeString(tempDir.resolve("codec_meta.json"), "{}");

        assertTrue(TtsModelInfo.isModelDirectoryComplete(info, tempDir));
    }

    private static TtsModelInfo find(List<TtsModelInfo> catalog, String name) {
        return catalog.stream()
                .filter(info -> info != null && name.equals(info.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TTS model not found: " + name));
    }
}
