package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmModelManagerTest {

    @Test
    void legacyCatalogWithRetiredMtpFieldsStillParses() {
        // 发布过的旧数据文件带 mtpDraftModelFile / mtpDraftHfFilePath；这两个字段已从 LlmModelInfo 删除，
        // 读取必须向后兼容——旧键只能被忽略，不能让整份清单解析失败（失败会静默退化为空清单）。
        String legacyCatalog = """
                [
                  {
                    "name": "legacy-model",
                    "modelFile": "legacy.gguf",
                    "mtpDraftModelFile": "mtp-legacy.gguf",
                    "mtpDraftHfFilePath": "mtp-legacy.gguf",
                    "contextSize": 8192,
                    "lang": ["zh", "en"]
                  }
                ]
                """;

        List<LlmModelInfo> models = LlmModelManager.parseCatalog(new StringReader(legacyCatalog));

        assertEquals(1, models.size(), "带已删除字段的旧清单必须仍能解析");
        LlmModelInfo info = models.get(0);
        assertEquals("legacy-model", info.name);
        assertEquals("legacy.gguf", info.getModelFile());
        assertEquals(8192, info.getContextSize());
        assertEquals(List.of("zh", "en"), info.getLang());
    }

    @Test
    void bundledCatalogStillResolvesChatEntries() {
        List<LlmModelInfo> models = LlmModelManager.getAllModels();

        assertFalse(models.isEmpty(), "聊天模型清单仍需可加载：云端请求的采样默认值按模型名查这份清单");
        LlmModelInfo first = models.get(0);
        assertNotNull(LlmModelManager.getModelByName(first.name));
        assertSame(first, LlmModelManager.getModelByName(first.name));
    }
    @Test
    void everyEmbeddingEntryIsLanguageNeutralAndLoaded() {
        LlmModelInfo shipped = LlmModelManager.getDefaultEmbeddingModel("en_us");

        assertNotNull(shipped);
        assertFalse(shipped.getModelFile().isBlank(), "the entry names the file it downloads");
        assertEquals(8192, shipped.getContextSize(), "bge-m3 is capped at 8192 tokens");
        assertTrue(shipped.supportsLanguage("zh") && shipped.supportsLanguage("en"),
                "one multilingual model serves both languages");
    }
}
