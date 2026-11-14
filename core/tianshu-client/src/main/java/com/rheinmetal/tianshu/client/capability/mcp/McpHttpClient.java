package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** MCP over streamable HTTP; the protocol layer lives in {@link AbstractMcpClient}. */
public final class McpHttpClient extends AbstractMcpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String sessionId;

    public McpHttpClient(String url) {
        this.endpoint = URI.create(url);
    }

    @Override
    protected JsonObject exchange(JsonObject request) throws IOException, InterruptedException {
        HttpResponse<String> response = send(request, true);
        if (response.statusCode() >= 300) {
            throw new IOException("MCP HTTP " + response.statusCode());
        }
        if (response.body().isBlank()) {
            return null;
        }
        JsonElement payload = parsePayload(response);
        if (payload == null || !payload.isJsonObject()) {
            throw new IOException("MCP returned no JSON object");
        }
        return payload.getAsJsonObject();
    }

    @Override
    protected void sendNotification(JsonObject notification) throws IOException, InterruptedException {
        HttpResponse<String> response = send(notification, false);
        if (response.statusCode() >= 300) {
            throw new IOException("MCP notification answered HTTP " + response.statusCode());
        }
    }

    @Override
    public void close() {
        // HTTP has no live connection to release; the session ends when the client is dropped.
    }

    private HttpResponse<String> send(JsonObject body, boolean acceptSse)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", acceptSse ? "application/json, text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        String session = sessionId;
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        return response;
    }

    /** Streamable HTTP may answer JSON or a single SSE frame; accept both. */
    private static JsonElement parsePayload(HttpResponse<String> response) {
        String body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            for (String line : body.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).strip();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        return JsonParser.parseString(data);
                    }
                }
            }
            return null;
        }
        return JsonParser.parseString(body);
    }
}
