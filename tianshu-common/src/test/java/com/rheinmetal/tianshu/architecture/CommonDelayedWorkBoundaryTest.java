package com.rheinmetal.tianshu.architecture;

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
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/function/ir/IrModule.java",
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/core/llm/AXLlmRagClient.java",
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/core/llm/AXLlmPrimitiveClient.java",
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/module/gamecontext/AXDynamicFactClient.java"
        );
        for (String relativeSource : relativeSources) {
            Path source = Path.of(relativeSource);
            if (!Files.exists(source)) {
                source = Path.of("..", relativeSource);
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("CompletableFuture.delayedExecutor"), relativeSource);
        }
    }
}
