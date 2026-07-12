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
        assertEquals("以下信息与玩家当前所处的情况相关：", texts.text(AXPromptTexts.GAME_CONTEXT_CURRENT_SITUATION_INTRO));
        assertEquals(
                "你记得此前与玩家发生过这些事情：\n- 玩家曾询问铁砧。",
                texts.render(AXPromptTexts.PLAYER_MEMORY_REMEMBERED_HISTORY_GROUP, Map.of("summaries", "- 玩家曾询问铁砧。"))
        );
        assertEquals("", texts.text("section.recent_dialogue"));
    }

    @Test
    void releasesDefaultPromptProfilesAndLoadsLocalizedProfile() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptProfile profile = repository.loadProfile(AXPromptTask.GENERAL_AX, AXPromptLanguage.ZH_CN, "default");

        assertTrue(Files.isRegularFile(layout.promptsRoot().resolve("general_ax.en_us.default.json")));
        assertTrue(Files.isRegularFile(layout.promptsRoot().resolve("general_ax.zh_cn.default.json")));
        assertTrue(profile.systemPrompts().standardPrompt().contains("随行聊天助手"));
        assertTrue(profile.systemPrompts().shortPrompt().contains("简洁"));
        assertTrue(profile.systemPrompts().fullPrompt().contains("信息不足"));
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
                          "schemaVersion": 2,
                          "texts": {
                            "game_context.current_situation_intro": {
                              "zh_cn": "当前情况："
                            },
                            "game_context.fact_line": {
                              "zh_cn": "* {{fact}}"
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        AXPromptResourceRepository repository = new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment()));

        AXPromptTexts texts = repository.loadTexts(AXPromptLanguage.ZH_CN);

        assertEquals("当前情况：", texts.text(AXPromptTexts.GAME_CONTEXT_CURRENT_SITUATION_INTRO));
        assertEquals("* 铁砧", texts.render(AXPromptTexts.GAME_CONTEXT_FACT_LINE, Map.of("fact", "铁砧")));
    }

    @Test
    void externalPromptProfileCanOverrideBuiltInProfile() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_ax.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 3,
                  "systemPrompts": {
                    "short": "完整的短提示词",
                    "standard": "完整的测试提示词",
                    "full": "完整的完整提示词"
                  },
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

        assertEquals("完整的测试提示词", profile.systemPrompts().standardPrompt());
        assertEquals("完整的短提示词", profile.systemPrompts().shortPrompt());
        assertEquals("完整的完整提示词", profile.systemPrompts().fullPrompt());
        assertEquals(List.of("ax_system", "game_context", "current_input"), profile.sectionOrder());
    }
}
