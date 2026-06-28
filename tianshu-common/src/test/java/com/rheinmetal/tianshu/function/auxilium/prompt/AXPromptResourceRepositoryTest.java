package com.rheinmetal.tianshu.function.auxilium.prompt;

import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXPromptResourceRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void releasesPromptTextCatalogAndLoadsBuiltInSections() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptTexts texts = repository.loadTexts(AXPromptLanguage.ZH_CN);

        assertTrue(Files.isRegularFile(layout.promptTextsFile()));
        assertEquals("<recent_dialogue>\n内容\n</recent_dialogue>", texts.render(AXPromptTexts.SECTION_RECENT_DIALOGUE, Map.of("content", "内容")));
    }

    @Test
    void externalPromptTextCatalogCanOverrideSectionShape() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptTextsFile(),
                        """
                        {
                          "schemaVersion": 1,
                          "texts": {
                            "section.recent_dialogue": {
                              "zh_cn": "[RECENT]\\n{{content}}\\n[/RECENT]"
                            },
                            "recent_dialogue.line": {
                              "zh_cn": "* {{speaker}}: {{message}}"
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptTexts texts = repository.loadTexts(AXPromptLanguage.ZH_CN);

        assertEquals("[RECENT]\n内容\n[/RECENT]", texts.render(AXPromptTexts.SECTION_RECENT_DIALOGUE, Map.of("content", "内容")));
        assertEquals("* Steve: 你好", texts.render(AXPromptTexts.RECENT_DIALOGUE_LINE, Map.of("speaker", "Steve", "message", "你好")));
    }
}
