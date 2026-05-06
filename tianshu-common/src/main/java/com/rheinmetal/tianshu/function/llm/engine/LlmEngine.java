package com.rheinmetal.tianshu.function.llm.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.IGameEnvironment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LlmEngine {
    public enum FinishReason {
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public record ChatMessage(String role, String content) {
        public ChatMessage {
            if (role == null || role.isBlank()) role = "user";
            if (content == null) content = "";
        }
    }

    private final IGameEnvironment env;
    private final HttpClient httpClient;
    private String baseUrl;
    private volatile InputStream currentStream;
    private final AtomicLong activeRequestId = new AtomicLong(0L);

    public LlmEngine(IGameEnvironment env, String baseUrl) {
        this.env = env;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public void initialize(String baseUrl) {
        this.baseUrl = baseUrl;
        env.info("Initializing LLM engine, baseUrl: " + baseUrl);
    }

    public long streamChat(String prompt, Consumer<String> onChunk, Consumer<FinishReason> onFinish, Consumer<String> onError) {
        return streamChat(List.of(new ChatMessage("user", prompt)), 0.6D, true, false, onChunk, onFinish, onError);
    }

    public long streamChat(List<ChatMessage> messages, double temperature, boolean stream, boolean thinking, Consumer<String> onChunk, Consumer<FinishReason> onFinish, Consumer<String> onError) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            env.error("LLM engine is not initialized: baseUrl is empty", null);
            onError.accept("LLM service URL is empty");
            return activeRequestId.get();
        }

        long requestId = activeRequestId.incrementAndGet();
        env.info("LLM request started, requestId=" + requestId);

        Thread streamThread = new Thread(() -> {
            FinishReason finishReason = FinishReason.FAILED;
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.add("messages", toJsonMessages(messages));
                requestBody.addProperty("temperature", normalizeTemperature(temperature));
                requestBody.addProperty("stream", stream);
                requestBody.addProperty("thinking", thinking);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    onError.accept("LLM service returned status code: " + response.statusCode());
                    return;
                }

                currentStream = response.body();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (requestId != activeRequestId.get()) {
                            finishReason = FinishReason.CANCELLED;
                            break;
                        }
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String data = line.substring(6);
                        if (data.equals("[DONE]")) {
                            finishReason = FinishReason.COMPLETED;
                            break;
                        }
                        try {
                            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                            if (!chunk.has("choices")) {
                                continue;
                            }
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices == null || choices.isEmpty()) {
                                continue;
                            }
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            if (!choice.has("delta")) {
                                continue;
                            }
                            JsonObject delta = choice.getAsJsonObject("delta");
                            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                String content = delta.get("content").getAsString();
                                if (!content.isEmpty() && requestId == activeRequestId.get()) {
                                    onChunk.accept(content);
                                }
                            }
                        } catch (Exception e) {
                            env.error("Failed to parse LLM stream line: " + line, e);
                        }
                    }
                }
            } catch (IOException e) {
                if (requestId != activeRequestId.get() || isExpectedCancellation(e)) {
                    finishReason = FinishReason.CANCELLED;
                } else {
                    env.error("LLM network request failed", e);
                    onError.accept("LLM network error: " + e.getMessage());
                    finishReason = FinishReason.FAILED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (requestId != activeRequestId.get()) {
                    finishReason = FinishReason.CANCELLED;
                } else {
                    env.error("LLM request interrupted", e);
                    onError.accept("LLM request interrupted");
                    finishReason = FinishReason.FAILED;
                }
            } finally {
                if (currentStream != null) {
                    try {
                        currentStream.close();
                    } catch (IOException e) {
                        env.error("Failed to close LLM stream", e);
                    }
                    currentStream = null;
                }
                onFinish.accept(finishReason);
            }
        }, "Tianshu-LLM-Stream-" + requestId);
        streamThread.setDaemon(true);
        streamThread.start();
        return requestId;
    }

    private JsonArray toJsonMessages(List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (messages == null || messages.isEmpty()) {
            array.add(toJsonMessage("user", ""));
            return array;
        }
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            array.add(toJsonMessage(message.role(), message.content()));
        }
        if (array.isEmpty()) {
            array.add(toJsonMessage("user", ""));
        }
        return array;
    }

    private JsonObject toJsonMessage(String role, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("role", normalizeRole(role));
        json.addProperty("content", content == null ? "" : content);
        return json;
    }

    private String normalizeRole(String role) {
        if ("system".equals(role) || "assistant".equals(role) || "user".equals(role)) {
            return role;
        }
        return "user";
    }

    private double normalizeTemperature(double temperature) {
        if (temperature <= 0.0D || temperature > 2.0D || Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            return 0.6D;
        }
        return temperature;
    }

    private boolean isExpectedCancellation(IOException e) {
        String message = e.getMessage();
        if (message == null) return false;
        return message.contains("closed") ||
               message.contains("subscription cancelled") ||
               message.contains("chunked transfer encoding");
    }

    public void cancelGeneration() {
        activeRequestId.incrementAndGet();
        if (currentStream != null) {
            env.info("Cancelling LLM generation");
            try {
                currentStream.close();
            } catch (IOException e) {
                env.error("Failed to close LLM stream", e);
            } finally {
                currentStream = null;
            }
        }
    }

    public void shutdown() {
        cancelGeneration();
        env.info("LLM engine closed");
    }
}
