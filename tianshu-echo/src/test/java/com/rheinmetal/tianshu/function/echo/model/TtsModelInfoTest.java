package com.rheinmetal.tianshu.function.echo.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsModelInfoTest {
    @Test
    void catalogLoadsCurrentTtsModelResource() {
        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();

        assertFalse(catalog.isEmpty());
        assertNotNull(find(catalog, "MOSS-TTS-Nano-100M-ONNX"));
        assertNotNull(find(catalog, "PocketTTS-ZH-4L-ONNX"));
        assertNotNull(find(catalog, "kokoro-multi-lang-v1_1"));
    }

    @Test
    void catalogKeepsTheDownloadUriFieldWithoutTheLegacyDownloadUrlField() throws Exception {
        TtsModelInfo info = find(TtsModelInfo.loadCatalog(), "vits-melo-tts-zh_en");

        assertNull(TtsModelInfo.class.getField("downloadUri").get(info));
        assertThrows(NoSuchFieldException.class, () -> TtsModelInfo.class.getField("downloadUrl"));
    }

    @Test
    void voiceCloneSupportIsLimitedToMossAndPocketByDefault() {
        List<TtsModelInfo> catalog = TtsModelInfo.loadCatalog();

        assertTrue(find(catalog, "MOSS-TTS-Nano-100M-ONNX").supportsVoiceClone());
        assertTrue(find(catalog, "PocketTTS-ZH-4L-ONNX").supportsVoiceClone());
        assertFalse(find(catalog, "kokoro-multi-lang-v1_1").supportsVoiceClone());
        assertFalse(find(catalog, "vits-melo-tts-zh_en").supportsVoiceClone());
    }

    @Test
    void ttsCatalogUsesTenPointScores() {
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
    void pocketDirectoryRequiresEveryDeclaredGraph(@TempDir Path tempDir) throws Exception {
        TtsModelInfo info = find(TtsModelInfo.loadCatalog(), "PocketTTS-ZH-4L-ONNX");

        Files.createFile(tempDir.resolve("flowlm_step.onnx"));

        assertFalse(TtsModelInfo.isModelDirectoryComplete(info, tempDir));

        for (String file : info.modelFiles) {
            Path target = tempDir.resolve(file);
            if (!Files.exists(target)) {
                Files.createFile(target);
            }
        }

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

    @Test
    void mossCatalogDeclaresItsSecondRepository() {
        TtsModelInfo moss = find(TtsModelInfo.loadCatalog(), "MOSS-TTS-Nano-100M-ONNX");

        assertEquals("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", moss.codecRepoId);
    }

    @Test
    void everyCatalogModelResolvesUnderTheModelRoot() {
        for (TtsModelInfo info : TtsModelInfo.loadCatalog()) {
            Path directory = TtsModelLayout.modelDirectory(Path.of("models"), info);

            assertEquals(Path.of("models", "model").resolve(info.name), directory);
        }
    }

    private static TtsModelInfo find(List<TtsModelInfo> catalog, String name) {
        return catalog.stream()
                .filter(info -> info != null && name.equals(info.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TTS model not found: " + name));
    }
}
