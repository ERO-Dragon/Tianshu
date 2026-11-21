package com.rheinmetal.tianshu.dubhe.module.memory.maintenance;

import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheMemoryTaskPromptRepositoryTest {
    private static final DubheScope SCOPE = new DubheScope("player", "save:test", "Test", DubheScopeKind.LOCAL_WORLD, true);

    @TempDir
    Path tempDir;

    @Test
    void releasesJsonCatalogAndRendersCompactInstruction() {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        DubheMemoryTaskPromptRepository repository = new DubheMemoryTaskPromptRepository(
                layout,
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN)
        );

        String instruction = repository.compactInstruction("save:测试世界", 3);

        assertTrue(Files.isRegularFile(layout.memoryTaskPromptsFile()));
        assertTrue(instruction.contains("3"), instruction);
        assertTrue(instruction.contains("save:测试世界"), instruction);
    }

    @Test
    void compactSystemPromptCarriesSchemaAndImportanceRules() {
        DubheMemoryTaskPromptRepository repository = new DubheMemoryTaskPromptRepository(
                new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US)
        );

        String system = repository.compactSystemPrompt();

        assertTrue(system.contains("schemaVersion"), "schema 必须写进同一份 system 提示词");
        assertTrue(system.contains("importance"), "importance 判据必须写进同一份 system 提示词");
        assertTrue(system.contains("turnRefs"), "轮级溯源要求必须写进同一份 system 提示词");
    }

    @Test
    void turnLinesCarryOneBasedIndexForTurnRefs() {
        DubheMemoryTaskPromptRepository repository = new DubheMemoryTaskPromptRepository(
                new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN)
        );

        DubheRawTurn user = DubheRawTurn.dialogue(SCOPE, "user", "我不喜欢下矿。", "session", "turn-1");
        DubheRawTurn assistant = DubheRawTurn.dialogue(SCOPE, "assistant", "记下了。", "session", "turn-1");
        // One line template for every turn kind: a speaker event puts the speaker in the role slot, so the
        // compression input still knows who said it without a second template.
        DubheRawTurn chatEvent = DubheRawTurn.worldEvent(SCOPE, "铁匠", "今天矿石不够了。", 1_000L, "line-1");
        DubheRawTurn plainEvent = DubheRawTurn.worldEvent(SCOPE, "玩家击杀了僵尸", 2_000L);

        assertEquals("[1] user: 我不喜欢下矿。", repository.compactTurnLine(user, 1));
        assertEquals("[2] assistant: 记下了。", repository.compactTurnLine(assistant, 2));
        assertEquals("[3] 铁匠: 今天矿石不够了。", repository.compactTurnLine(chatEvent, 3));
        assertEquals("[4] world_event: 玩家击杀了僵尸", repository.compactTurnLine(plainEvent, 4));
        assertEquals("", repository.compactTurnLine(null, 5));
    }

    @Test
    void loadsExternalJsonCatalogBeforeBuiltinCatalog() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Path promptDir = layout.promptsRoot();
        Files.createDirectories(promptDir);
        Files.writeString(
                layout.memoryTaskPromptsFile(),
                """
                        {
                          "schemaVersion": 2,
                          "prompts": {
                            "memory.compact.user": {
                              "zh_cn": "外部指令 count={{turnCount}} world={{world}}"
                            }
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        DubheMemoryTaskPromptRepository repository = new DubheMemoryTaskPromptRepository(
                layout,
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN)
        );

        assertEquals("外部指令 count=7 world=save:world", repository.compactInstruction("save:world", 7));
    }

    @Test
    void fallsBackToBuiltInPromptWhenExternalTemplateIsMissing() {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        DubheMemoryTaskPromptRepository repository = new DubheMemoryTaskPromptRepository(
                layout,
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US)
        );

        assertTrue(repository.compactSystemPrompt().contains("compression unit"));
        assertTrue(repository.compactInstruction("save:world", 0).contains("compression unit"));
    }
}
