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
 * The execution layer must not reach into the dialogue layer's voice boundary.
 *
 * <p>Workers report through the delivery contract; they never speak. If a worker could reference the chat output
 * or the speech sink, a report could bypass the three-channel discipline (registered / in prompt / spoken) that
 * keeps failures visible.</p>
 */
class DubheAgentLayerBoundaryTest {

    private static final List<String> FORBIDDEN_TYPES = List.of("DubheChatOutput", "DubheSpeechSink");

    @Test
    void theExecutionLayerNeverTouchesTheVoiceSide() throws IOException {
        Path agent = WorkspacePaths.require("tianshu-dubhe/src/main/java/com/rheinmetal/tianshu/dubhe/agent");

        List<Path> sources;
        try (var walk = Files.walk(agent)) {
            sources = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }

        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String type : FORBIDDEN_TYPES) {
                assertFalse(text.contains(type),
                        () -> source + " references the dialogue-layer type " + type
                                + "; workers report through the delivery contract instead of speaking");
            }
        }
    }
}