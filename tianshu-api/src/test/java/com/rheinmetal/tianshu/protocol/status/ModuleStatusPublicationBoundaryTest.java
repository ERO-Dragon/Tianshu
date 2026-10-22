package com.rheinmetal.tianshu.protocol.status;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleStatusPublicationBoundaryTest {
    @Test
    void downloadCompletionDoesNotClaimRuntimeReady() throws Exception {
        assertDownloadCompletionUsesWaiting("tianshu-intake/src/main/java/com/rheinmetal/tianshu/function/intake/AsrModelService.java", "module.asr");
        assertDownloadCompletionUsesWaiting("tianshu-weaving/src/main/java/com/rheinmetal/tianshu/function/weaving/LlmModelService.java", "module.llm");
        assertDownloadCompletionUsesWaiting("tianshu-echo/src/main/java/com/rheinmetal/tianshu/function/echo/TtsModelService.java", "module.tts");
    }

    private static void assertDownloadCompletionUsesWaiting(String relativePath, String moduleId) throws Exception {
        Path path = Path.of(relativePath);
        if (!Files.exists(path)) {
            path = Path.of("..", relativePath);
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String messagePrefix = "tianshu.presence." + moduleId + ".download_complete";
        assertTrue(source.contains("publishWaiting(\"" + messagePrefix), relativePath);
        assertFalse(source.contains("publishReady(\"" + messagePrefix), relativePath);
    }
}
