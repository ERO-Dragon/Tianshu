package com.rheinmetal.tianshu.client.capability.mcp;

import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The config file selects the transport and keeps the autonomy of each server. */
class McpConfigTest {

    @TempDir
    Path root;

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

        List<McpServerSpec> specs = McpConfig.load(config);
        assertEquals(2, specs.size());
        assertEquals(McpServerSpec.Type.HTTP, specs.get(0).type());
        assertEquals("http://127.0.0.1:1/mcp", specs.get(0).url());
        assertEquals(ToolAutonomy.CONSERVATIVE, specs.get(0).autonomy());

        assertEquals(McpServerSpec.Type.STDIO, specs.get(1).type());
        assertEquals(List.of("npx", "-y", "server"), specs.get(1).command());
        assertEquals(ToolAutonomy.FULL, specs.get(1).autonomy());
    }

    @Test
    void aMissingFileIsSimplyEmpty() {
        assertTrue(McpConfig.load(root.resolve("absent.json")).isEmpty());
    }
}