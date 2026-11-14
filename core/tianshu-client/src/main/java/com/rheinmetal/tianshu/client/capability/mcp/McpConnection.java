package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/** One MCP session, independent of the transport underneath it. */
public interface McpConnection extends AutoCloseable {

    void initialize() throws IOException, InterruptedException;

    List<JsonObject> listTools() throws IOException, InterruptedException;

    List<JsonObject> listResources() throws IOException, InterruptedException;

    JsonObject readResource(String uri) throws IOException, InterruptedException;

    List<JsonObject> listPrompts() throws IOException, InterruptedException;

    JsonObject getPrompt(String name, JsonObject arguments) throws IOException, InterruptedException;

    JsonObject callTool(String name, JsonObject arguments) throws IOException, InterruptedException;

    /** Closes without a checked exception: a broken session must not add another failure path. */
    @Override
    void close();
}
