package com.rheinmetal.tianshu.dubhe.architecture;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The dialogue layer owns no tools. Delegation is its only exit, so the tool vocabulary must not appear here at
 * all — not even as an import, a type reference or a comment-level Javadoc link.
 *
 * <p>This is stronger than reading a runtime flag: a flag can be flipped by configuration, a missing reference
 * cannot. The tool-round implementation lives in the execution layer and is wired there, not here.</p>
 */
class DubheDialogueLayerBoundaryTest {

    private static final List<String> FORBIDDEN = List.of(
            "TianshuTool",
            "ToolCaller",
            "CapabilityCatalog",
            "DubheToolTurn",
            "DubheToolRoundHandler");

    @Test
    void theDialogueLayerNeverTouchesTheToolVocabulary() throws IOException {
        Path dialogue = WorkspacePaths.require("tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/dialogue");

        List<Path> sources;
        try (var walk = Files.walk(dialogue)) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String token : FORBIDDEN) {
                assertFalse(text.contains(token),
                        () -> source + " references the tool vocabulary '" + token
                                + "'; the dialogue layer delegates instead of calling tools");
            }
        }
    }
}