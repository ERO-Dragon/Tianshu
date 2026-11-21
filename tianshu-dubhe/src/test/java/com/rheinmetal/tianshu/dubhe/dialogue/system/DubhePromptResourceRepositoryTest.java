package com.rheinmetal.tianshu.dubhe.dialogue.system;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePromptResourceRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void releasesPromptTextCatalogAndLoadsBuiltInSections() {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptTexts texts = repository.loadTexts(DubhePromptLanguage.ZH_CN);

        assertTrue(Files.isRegularFile(layout.promptTextsFile()));
        assertEquals("以下信息与玩家当前所处的情况相关：", texts.text(DubhePromptTexts.GAME_CONTEXT_CURRENT_SITUATION_INTRO));
        assertEquals(
                "你记得此前与玩家发生过这些事情：\n- 玩家曾询问铁砧。",
                texts.render(DubhePromptTexts.PLAYER_MEMORY_REMEMBERED_HISTORY_GROUP, Map.of("summaries", "- 玩家曾询问铁砧。"))
        );
        assertEquals("", texts.text("section.recent_dialogue"));
    }

    @Test
    void releasesDefaultPromptProfilesAndLoadsLocalizedProfile() {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptProfile profile = repository.loadProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.ZH_CN, "default");

        assertTrue(Files.isRegularFile(layout.promptsRoot().resolve("general_dubhe.zh_cn.default.json")));
        assertFalse(Files.exists(layout.promptsRoot().resolve("general_dubhe.en_us.default.json")),
                "提示词已收敛为单一文件：按玩家语言回答写在同一份 S 里");
        assertEquals(profile.systemPrompt(),
                repository.loadProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.EN_US, "default").systemPrompt(),
                "英文语种必须回退到同一份共享提示词，而不是 Java 默认档");
        assertTrue(profile.systemPrompt().contains("你是天枢"));
        assertTrue(profile.systemPrompt().contains("信息不足"));
        assertTrue(profile.sectionOrder().contains("system_prompt"));
        assertTrue(profile.sectionOrder().contains("game_context"));
        assertTrue(profile.sectionOrder().contains("player_memory"));
    }

    @Test
    void externalPromptTextCatalogCanOverrideSectionShape() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
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
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptTexts texts = repository.loadTexts(DubhePromptLanguage.ZH_CN);

        assertEquals("当前情况：", texts.text(DubhePromptTexts.GAME_CONTEXT_CURRENT_SITUATION_INTRO));
        assertEquals("* 铁砧", texts.render(DubhePromptTexts.GAME_CONTEXT_FACT_LINE, Map.of("fact", "铁砧")));
    }

    @Test
    void externalPromptProfileCanOverrideBuiltInProfile() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_dubhe.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 4,
                  "systemPrompts": {
                    "system": "完整的测试提示词"
                  },
                  "sectionOrder": [
                    "system_prompt",
                    "game_context",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptProfile profile = repository.loadProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.ZH_CN, "default");

        assertEquals("完整的测试提示词", profile.systemPrompt());
        assertEquals(List.of("system_prompt", "game_context", "current_input"), profile.sectionOrder());
    }

    @Test
    void legacyThreeTierProfileFallsBackToItsMostCompletePrompt() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_dubhe.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 3,
                  "systemPrompts": {
                    "short": "旧格式的短提示词",
                    "standard": "旧格式的标准提示词",
                    "full": "旧格式的完整提示词"
                  },
                  "sectionOrder": [
                    "system_prompt",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptProfile profile = repository.loadProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.ZH_CN, "default");

        assertEquals("旧格式的完整提示词", profile.systemPrompt());
    }

    @Test
    void legacyThreeTierProfileWithoutFullPromptFallsBackToStandardThenShort() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_dubhe.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 3,
                  "systemPrompts": {
                    "short": "旧格式的短提示词",
                    "standard": "旧格式的标准提示词"
                  }
                }
                """,
                StandardCharsets.UTF_8
        );
        DubhePromptResourceRepository repository = new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment()));

        DubhePromptProfile profile = repository.loadProfile(DubhePromptTask.GENERAL_DUBHE, DubhePromptLanguage.ZH_CN, "default");

        assertEquals("旧格式的标准提示词", profile.systemPrompt());
    }
}
