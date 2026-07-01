package com.rheinmetal.tianshu.function.auxilium.module.system;

import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void releasesDefaultPromptProfilesAndLoadsLocalizedProfile() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptProfile profile = repository.loadProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.ZH_CN, "default");

        assertTrue(Files.isRegularFile(layout.promptsRoot().resolve("general_ax.en_us.default.json")));
        assertTrue(Files.isRegularFile(layout.promptsRoot().resolve("general_ax.zh_cn.default.json")));
        assertEquals("你是天枢 Minecraft 模组中的随行聊天助手。", profile.identity());
        assertTrue(profile.sectionOrder().contains("ax_system"));
        assertTrue(profile.sectionOrder().contains("game_context"));
        assertTrue(profile.sectionOrder().contains("player_memory"));
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

    @Test
    void externalPromptProfileCanOverrideBuiltInProfile() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_ax.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 1,
                  "identity": "测试身份",
                  "behaviorRules": "测试规则",
                  "sectionOrder": [
                    "ax_system",
                    "game_context",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptProfile profile = repository.loadProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.ZH_CN, "default");

        assertEquals("测试身份", profile.identity());
        assertEquals("测试规则", profile.behaviorRules());
        assertEquals(List.of("ax_system", "game_context", "current_input"), profile.sectionOrder());
    }
}
