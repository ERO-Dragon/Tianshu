package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The child process behind {@link McpStdioClientTest}: a newline-delimited JSON-RPC endpoint on stdin/stdout.
 *
 * <p>It exists so the tests can exercise the real pipe instead of a mock. Two behaviours are on purpose:
 * every request is answered on its own thread, so a delayed answer arrives <em>after</em> a later one (that is
 * what would let two concurrent exchanges swap answers), and {@code noise} makes it write a non-JSON line and a
 * notification before the answer (that is what a child process printing to stdout looks like).</p>
 */
public final class McpStdioTestServer {

    private McpStdioTestServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "exit-now".equals(args[0])) {
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        ExecutorService replies = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcp-test-reply");
            thread.setDaemon(true);
            return thread;
        });
        String line;
        while ((line = in.readLine()) != null) {
            JsonObject request = parse(line);
            if (request == null || !request.has("id") || !request.has("method")) {
                continue;
            }
            JsonObject copy = request.deepCopy();
            replies.execute(() -> reply(copy, out));
        }
    }

    private static JsonObject parse(String line) {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static void reply(JsonObject request, PrintStream out) {
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        long delay = arguments.has("delayMs") ? arguments.get("delayMs").getAsLong() : 0L;
        if (delay > 0L) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (arguments.has("noise") && arguments.get("noise").getAsBoolean()) {
            out.println("this line is not json at all");
            out.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}");
        }

        JsonObject result = new JsonObject();
        switch (method) {
            case "initialize" -> result.addProperty("protocolVersion", "2024-11-05");
            case "tools/list" -> result.add("tools", array(tool()));
            case "tools/call" -> result.add("content", array(content(arguments)));
            default -> {
            }
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        response.add("result", result);
        synchronized (out) {
            out.println(response);
        }
    }

    private static JsonObject tool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "echo");
        tool.addProperty("description", "Echo the text back");
        tool.add("inputSchema", new JsonObject());
        return tool;
    }

    private static JsonObject content(JsonObject arguments) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", arguments.has("text") ? arguments.get("text").getAsString() : "");
        return text;
    }

    private static JsonArray array(JsonObject element) {
        JsonArray array = new JsonArray();
        array.add(element);
        return array;
    }
}