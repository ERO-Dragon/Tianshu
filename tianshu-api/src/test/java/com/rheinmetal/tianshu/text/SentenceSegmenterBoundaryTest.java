package com.rheinmetal.tianshu.text;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SentenceSegmenterBoundaryTest {
    @Test
    void dubheDoesNotDependOnTtsTextUtilities() throws Exception {
        Path source = WorkspacePaths.require("tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/core/output/DubheSentenceBuffer.java");

        String content = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(content.contains("tianshu.echo.text"));
    }
}
