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
        assertEquals("<game_chat>\n内容\n</game_chat>", texts.render(AXPromptTexts.SECTION_GAME_CHAT, Map.of("content", "内容")));
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
                            "section.game_chat": {
                              "zh_cn": "[GAME_CHAT]\\n{{content}}\\n[/GAME_CHAT]"
                            },
                            "game_chat.item_line": {
                              "zh_cn": "* {{message}}"
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptTexts texts = repository.loadTexts(AXPromptLanguage.ZH_CN);

        assertEquals("[GAME_CHAT]\n内容\n[/GAME_CHAT]", texts.render(AXPromptTexts.SECTION_GAME_CHAT, Map.of("content", "内容")));
        assertEquals("* Steve说：你好", texts.render(AXPromptTexts.GAME_CHAT_ITEM_LINE, Map.of("message", "Steve说：你好")));
    }
}
