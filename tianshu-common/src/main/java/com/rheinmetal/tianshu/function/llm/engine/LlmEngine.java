package com.rheinmetal.tianshu.function.llm.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationLane;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagHit;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagRoutingContext;

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
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

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

    public long beginStreamRequest(Consumer<String> onError) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            env.error("LLM engine is not initialized: baseUrl is empty", null);
            onError.accept("LLM service URL is empty");
            return -1L;
        }

        long requestId = activeRequestId.incrementAndGet();
        env.info("LLM request started, requestId=" + requestId);
        return requestId;
    }

    public void streamChatBlocking(long requestId, List<ChatMessage> messages, double temperature, boolean stream, boolean thinking, int maxTokens, LlmInvocationLane lane, boolean useRag, boolean useMemoryRag, int memoryRagTokenBudget, boolean includeRagHits, int taskPriority, boolean taskPreemptible, List<String> dynamicRag, LlmRagRoutingContext ragRouting, Consumer<String> onChunk, Consumer<LlmRagHit> onRagHit, Consumer<FinishReason> onFinish, Consumer<String> onError) {
        LlmInvocationLane effectiveLane = lane == null ? LlmInvocationLane.CHAT : lane;
        boolean effectiveStream = stream;
        FinishReason finishReason = FinishReason.FAILED;
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.add("messages", toJsonMessages(messages));
            requestBody.addProperty("lane", effectiveLane.wireName());
            requestBody.addProperty("temperature", normalizeTemperature(temperature));
            requestBody.addProperty("stream", effectiveStream);
            requestBody.addProperty("thinking", thinking);
            requestBody.addProperty("use_rag", useRag);
            requestBody.addProperty("include_rag_hits", includeRagHits);
            if (effectiveLane == LlmInvocationLane.CHAT) {
                requestBody.addProperty("use_memory_rag", useMemoryRag);
                if (memoryRagTokenBudget > 0) {
                    requestBody.addProperty("memory_rag_token_budget", memoryRagTokenBudget);
                }
            }
            if (effectiveLane == LlmInvocationLane.TASK) {
                requestBody.addProperty("task_priority", taskPriority);
                requestBody.addProperty("task_preemptible", taskPreemptible);
            }
            if (maxTokens > 0) {
                requestBody.addProperty("max_tokens", maxTokens);
            }
            if (dynamicRag != null && !dynamicRag.isEmpty()) {
                requestBody.add("dynamic_rag", toJsonStringArray(dynamicRag));
            }
            addRagRouting(requestBody, ragRouting);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", effectiveStream ? "text/event-stream" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                onError.accept(formatHttpError(response.statusCode(), response.body()));
                return;
            }

            currentStream = response.body();

            if (!effectiveStream) {
                String responseBody = readAll(currentStream);
                emitRagHits(responseBody, onRagHit);
                String content = parseCompletionContent(responseBody);
                if (!content.isEmpty() && requestId == activeRequestId.get()) {
                    onChunk.accept(content);
                }
                finishReason = requestId == activeRequestId.get() ? FinishReason.COMPLETED : FinishReason.CANCELLED;
                return;
            }

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
                        emitRagHits(chunk, onRagHit);
                        if (!chunk.has("choices")) {
                            continue;
                        }
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }
                        JsonObject choice = choices.get(0).getAsJsonObject();
                        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                            finishReason = FinishReason.COMPLETED;
                        }
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
                        env.error("Failed to parse LLM stream chunk", e);
                    }
                }
            }
        } catch (IOException e) {
            if (requestId != activeRequestId.get() || isExpectedCancellation(e)) {
                finishReason = FinishReason.CANCELLED;
            } else {
                env.error("LLM network request failed", e);
                onError.accept("LLM network error");
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
    }

    private String formatHttpError(int statusCode, InputStream body) {
        closeQuietly(body);
        return "LLM service returned status code: " + statusCode;
    }

    private String readAll(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        } catch (IOException e) {
            env.error("Failed to read LLM response body", e);
            return "";
        }
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            env.error("Failed to close LLM response body", e);
        }
    }

    private void emitRagHits(String responseBody, Consumer<LlmRagHit> onRagHit) {
        if (responseBody == null || responseBody.isBlank()) {
            return;
        }
        try {
            emitRagHits(JsonParser.parseString(responseBody).getAsJsonObject(), onRagHit);
        } catch (Exception e) {
            env.error("Failed to parse LLM RAG hits", e);
        }
    }

    private void emitRagHits(JsonObject json, Consumer<LlmRagHit> onRagHit) {
        if (json == null || onRagHit == null || !json.has("rag_hits") || !json.get("rag_hits").isJsonObject()) {
            return;
        }
        JsonObject ragHits = json.getAsJsonObject("rag_hits");
        if (!ragHits.has("memory") || !ragHits.get("memory").isJsonArray()) {
            return;
        }
        JsonArray memory = ragHits.getAsJsonArray("memory");
        for (int i = 0; i < memory.size(); i++) {
            if (!memory.get(i).isJsonObject()) {
                continue;
            }
            JsonObject hit = memory.get(i).getAsJsonObject();
            String uid = hit.has("uid") && !hit.get("uid").isJsonNull() ? hit.get("uid").getAsString() : "";
            double score = hit.has("score") && !hit.get("score").isJsonNull() ? hit.get("score").getAsDouble() : 0.0D;
            String text = "";
            if (hit.has("long_term_memory") && !hit.get("long_term_memory").isJsonNull()) {
                text = hit.get("long_term_memory").getAsString();
            } else if (hit.has("text") && !hit.get("text").isJsonNull()) {
                text = hit.get("text").getAsString();
            } else if (hit.has("content") && !hit.get("content").isJsonNull()) {
                text = hit.get("content").getAsString();
            }
            if (!uid.isBlank()) {
                onRagHit.accept(new LlmRagHit("memory", uid, score, text));
            }
        }
    }

    private String parseCompletionContent(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!json.has("choices")) {
                return "";
            }
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (!choice.has("message")) {
                return "";
            }
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                return "";
            }
            return message.get("content").getAsString();
        } catch (Exception e) {
            env.error("Failed to parse LLM completion response", e);
            return "";
        }
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

    private void addRagRouting(JsonObject requestBody, LlmRagRoutingContext ragRouting) {
        if (requestBody == null || ragRouting == null || ragRouting.isEmpty()) {
            return;
        }
        if (!ragRouting.world().isBlank()) {
            requestBody.addProperty("world", ragRouting.world());
        }
        if (!ragRouting.profile().isBlank()) {
            requestBody.addProperty("profile", ragRouting.profile());
        }
        if (!ragRouting.staticScope().isBlank()) {
            requestBody.addProperty("static_scope", ragRouting.staticScope());
        }
        if (!ragRouting.staticMods().isEmpty()) {
            requestBody.add("static_mods", toJsonStringArray(ragRouting.staticMods()));
        }
    }

    private JsonArray toJsonStringArray(List<String> values) {
        JsonArray array = new JsonArray();
        if (values == null || values.isEmpty()) {
            return array;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        return array;
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase();
        if ("system".equals(normalized) || "assistant".equals(normalized) || "user".equals(normalized)) {
            return normalized;
        }
        return "user";
    }

    private double normalizeTemperature(double temperature) {
        if (temperature < 0.0D || temperature > 2.0D || Double.isNaN(temperature) || Double.isInfinite(temperature)) {
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
