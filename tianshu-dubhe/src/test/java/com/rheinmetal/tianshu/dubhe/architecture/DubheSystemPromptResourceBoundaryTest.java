package com.rheinmetal.tianshu.dubhe.architecture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The system prompt is the cached prefix; naming a worker there would make every worker addition change it.
 *
 * <p>This is the text assertion half of the layering rules: the internal worker directory must never reach the
 * frozen system text. Worker ids are matched as whole tokens, because names such as {@code memory_summary}
 * legitimately appear in section names.</p>
 */
class DubheSystemPromptResourceBoundaryTest {

    private static final List<String> WORKER_IDS = List.of("research", "memory", "diplomacy", "fallback");

    @Test
    void theSystemPromptNeverNamesAWorker() throws Exception {
        Path profile = WorkspacePaths.require(
                "tianshu-dubhe/src/main/resources/com/rheinmetal/tianshu/dubhe/prompts/general_dubhe.zh_cn.default.json");
        JsonObject root = JsonParser.parseString(Files.readString(profile, StandardCharsets.UTF_8)).getAsJsonObject();
        String system = root.getAsJsonObject("systemPrompts").get("system").getAsString();

        for (String worker : WORKER_IDS) {
            Matcher named = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(worker) + "(?![A-Za-z0-9_])")
                    .matcher(system);
            assertFalse(named.find(),
                    () -> "the frozen system prompt must not name the worker '" + worker
                            + "'; the worker directory would then change the cached prefix");
        }
    }
}