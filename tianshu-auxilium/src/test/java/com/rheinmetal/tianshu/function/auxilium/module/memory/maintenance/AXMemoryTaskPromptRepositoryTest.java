package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryTaskPromptRepositoryTest {
    private static final AXScope SCOPE = new AXScope("player", "save:test", "Test", AXScopeKind.LOCAL_WORLD, true);

    @TempDir
    Path tempDir;

    @Test
    void releasesJsonCatalogAndRendersCompactInstruction() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                layout,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
        );

        String instruction = repository.compactInstruction("save:测试世界", 3);

        assertTrue(Files.isRegularFile(layout.memoryTaskPromptsFile()));
        assertTrue(instruction.contains("3"), instruction);
        assertTrue(instruction.contains("save:测试世界"), instruction);
    }

    @Test
    void compactSystemPromptCarriesSchemaAndImportanceRules() {
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US)
        );

        String system = repository.compactSystemPrompt();

        assertTrue(system.contains("schemaVersion"), "schema 必须写进同一份 system 提示词");
        assertTrue(system.contains("importance"), "importance 判据必须写进同一份 system 提示词");
        assertTrue(system.contains("turnRefs"), "轮级溯源要求必须写进同一份 system 提示词");
    }

    @Test
    void turnLinesCarryOneBasedIndexForTurnRefs() {
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
        );

        AXRawTurn user = AXRawTurn.dialogue(SCOPE, "user", "我不喜欢下矿。", "session", "turn-1");
        AXRawTurn assistant = AXRawTurn.dialogue(SCOPE, "assistant", "记下了。", "session", "turn-1");

        assertEquals("[1] user: 我不喜欢下矿。", repository.compactTurnLine(user, 1));
        assertEquals("[2] assistant: 记下了。", repository.compactTurnLine(assistant, 2));
        assertEquals("", repository.compactTurnLine(null, 3));
    }

    @Test
    void loadsExternalJsonCatalogBeforeBuiltinCatalog() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
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
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                layout,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
        );

        assertEquals("外部指令 count=7 world=save:world", repository.compactInstruction("save:world", 7));
    }

    @Test
    void fallsBackToBuiltInPromptWhenExternalTemplateIsMissing() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                layout,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US)
        );

        assertTrue(repository.compactSystemPrompt().contains("compression unit"));
        assertTrue(repository.compactInstruction("save:world", 0).contains("compression unit"));
    }
}
