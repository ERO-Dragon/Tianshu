package com.rheinmetal.tianshu.client.capability.mcp;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The config file selects the transport and keeps the autonomy of each server. */
class McpConfigTest {

    @TempDir
    Path root;

    private final List<String> warnings = new ArrayList<>();
    private final LogSink recorder = new LogSink() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public void error(String message, Throwable failure) { warnings.add(message); }
    };

    @Test
    void httpAndStdioServersAreParsed() throws Exception {
        Path config = root.resolve("mcp.json");
        Files.writeString(config, """
                {
                  "servers": [
                    {"name": "docs", "type": "http", "url": "http://127.0.0.1:1/mcp", "autonomy": "conservative"},
                    {"name": "local", "type": "stdio", "command": ["npx", "-y", "server"], "autonomy": "full"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        List<McpServerSpec> specs = McpConfig.load(config, recorder);
        assertEquals(2, specs.size());
        assertEquals(McpServerSpec.Type.HTTP, specs.get(0).type());
        assertEquals("http://127.0.0.1:1/mcp", specs.get(0).url());
        assertEquals(ToolAutonomy.CONSERVATIVE, specs.get(0).autonomy());

        assertEquals(McpServerSpec.Type.STDIO, specs.get(1).type());
        assertEquals(List.of("npx", "-y", "server"), specs.get(1).command());
        assertEquals(ToolAutonomy.FULL, specs.get(1).autonomy());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aMissingFileIsSimplyEmpty() {
        assertTrue(McpConfig.load(root.resolve("absent.json"), recorder).isEmpty());
        assertTrue(warnings.isEmpty(), "not configuring MCP is normal, not a warning: " + warnings);
    }

    @Test
    void aMalformedFileIsReportedInsteadOfLookingUnconfigured() throws Exception {
        Path config = root.resolve("broken.json");
        Files.writeString(config, "{\"servers\": [", StandardCharsets.UTF_8);

        assertTrue(McpConfig.load(config, recorder).isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("not valid JSON"), warnings.get(0));
    }

    @Test
    void anEntryWithoutANameOrEndpointIsReportedButTheRestLoad() throws Exception {
        Path config = root.resolve("partial.json");
        Files.writeString(config, """
                {"servers": [
                  {"name": "docs", "url": "http://127.0.0.1:1/mcp"},
                  {"name": "", "url": "http://127.0.0.1:2/mcp"},
                  "not an object"
                ]}
                """, StandardCharsets.UTF_8);

        List<McpServerSpec> specs = McpConfig.load(config, recorder);

        assertEquals(1, specs.size());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("2 server entries"), warnings.get(0));
    }

    @Test
    void aServerThatDoesNotStateItsAutonomyGetsTheConservativeTier() throws Exception {
        Path config = root.resolve("default-autonomy.json");
        Files.writeString(config, """
                {"servers": [
                  {"name": "docs", "url": "http://127.0.0.1:1/mcp"},
                  {"name": "typo", "url": "http://127.0.0.1:2/mcp", "autonomy": "chat"}
                ]}
                """, StandardCharsets.UTF_8);

        List<McpServerSpec> specs = McpConfig.load(config, recorder);

        assertEquals(2, specs.size());
        assertEquals(ToolAutonomy.CONSERVATIVE, specs.get(0).autonomy(),
                "a server that says nothing must not be handed the privileged tier by omission");
        assertEquals(ToolAutonomy.CONSERVATIVE, specs.get(1).autonomy(),
                "an unreadable tier must fall back to the safe one, not the privileged one");
    }
}