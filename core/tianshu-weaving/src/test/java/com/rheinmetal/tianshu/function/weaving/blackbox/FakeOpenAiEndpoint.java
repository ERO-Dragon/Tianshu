package com.rheinmetal.tianshu.function.weaving.blackbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real HTTP endpoint on a loopback port, speaking the OpenAI chat-completions protocol.
 *
 * <p>Black-box tests must not stub the transport: they drive the module's own client over a socket,
 * so request encoding, SSE framing, status handling and deadlines are all exercised. The handler is a
 * lambda because each scenario is a different provider behaviour — a normal answer, a malformed
 * frame, a filter stop, a silent socket.
 */
final class FakeOpenAiEndpoint implements AutoCloseable {

    /** One scenario's answer. The exchange is closed by the server afterwards, unless the handler did. */
    interface Handler {
        void handle(JsonObject body, HttpExchange exchange) throws IOException, InterruptedException;
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    private FakeOpenAiEndpoint(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static FakeOpenAiEndpoint start(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        FakeOpenAiEndpoint endpoint = new FakeOpenAiEndpoint(server, executor);
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> endpoint.serve(handler, exchange));
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return endpoint;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Every chat-completions body received so far, in arrival order. */
    List<JsonObject> requests() {
        return List.copyOf(requests);
    }

    JsonObject onlyRequest() {
        List<JsonObject> received = requests();
        if (received.size() != 1) {
            throw new AssertionError("expected exactly one chat request, got " + received.size());
        }
        return received.get(0);
    }

    private void serve(Handler handler, HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        requests.add(json);
        try {
            handler.handle(json, exchange);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    /** Sends one SSE response: headers first, then every frame as its own {@code data:} event. */
    static void sse(HttpExchange exchange, String... frames) throws IOException {
        beginSse(exchange);
        for (String frame : frames) {
            write(exchange, frame + "\n\n");
        }
    }

    static void beginSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
    }

    static void write(HttpExchange exchange, String text) throws IOException {
        OutputStream out = exchange.getResponseBody();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The four frames of a normal completion: content, optional usage, optional thinking, then DONE. */
    static List<String> completion(String content, Integer promptTokens, Integer completionTokens,
                                   String thinkingField, String thinking) {
        List<String> frames = new ArrayList<>();
        if (thinking != null && !thinking.isEmpty()) {
            frames.add("data: {\"choices\":[{\"delta\":{\"" + thinkingField + "\":\""
                    + escape(thinking) + "\"}}]}");
        }
        frames.add("data: {\"choices\":[{\"delta\":{\"content\":\"" + escape(content) + "\"}}]}");
        if (promptTokens != null && completionTokens != null) {
            frames.add("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":" + promptTokens
                    + ",\"completion_tokens\":" + completionTokens + "}}");
        }
        frames.add("data: [DONE]");
        return frames;
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}