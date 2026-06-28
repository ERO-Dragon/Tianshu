package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryTaskPromptRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void releasesJsonCatalogAndRendersVariables() {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                layout,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN)
        );

        String prompt = repository.compressionUserPrompt("save:测试世界", "user: 你好");

        assertTrue(Files.isRegularFile(layout.memoryTaskPromptsFile()));
        assertEquals("user: 你好", prompt);
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
                          "schemaVersion": 1,
                          "prompts": {
                            "memory.extract.user": {
                              "zh_cn": "JSON STM={{stm}}"
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

        assertEquals("JSON STM=一段记忆", repository.extractionUserPrompt("一段记忆"));
    }

    @Test
    void fallsBackToBuiltInPromptWhenTemplateIsMissing() {
        AXMemoryTaskPromptRepository repository = new AXMemoryTaskPromptRepository(
                new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module"))),
                AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US)
        );

        assertTrue(repository.extractionSystemPrompt().contains("objective atomic facts"));
        assertTrue(repository.extractionUserPrompt("STM body").contains("STM body"));
    }
}
