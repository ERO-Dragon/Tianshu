package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CommonDelayedWorkBoundaryTest {
    @Test
    void functionModulesDoNotOwnIndependentDelayedExecutors() throws Exception {
        List<String> relativeSources = List.of(
                "tianshu-intake/src/main/java/com/rheinmetal/tianshu/intake/repair/AsrTextRepairer.java",
                "tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/core/llm/DubheLlmRagClient.java",
                "tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/core/llm/DubheLlmPrimitiveClient.java",
                "tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/module/gamecontext/DubheDynamicFactClient.java"
        );
        for (String relativeSource : relativeSources) {
            Path source = WorkspacePaths.require(relativeSource);
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("CompletableFuture.delayedExecutor"), relativeSource);
        }
    }
}
