package com.rheinmetal.tianshu.core.Engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LlmEngine {
    public enum FinishReason {
        COMPLETED,
        CANCELLED,
        FAILED
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
        env.info("初始化 LLM 引擎，baseUrl: " + baseUrl);
    }

    public long streamChat(String prompt, Consumer<String> onChunk, Consumer<FinishReason> onFinish, Consumer<String> onError) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            env.error("LLM 引擎未初始化，baseUrl 为空", null);
            onError.accept("LLM 服务地址为空");
            return activeRequestId.get();
        }

        long requestId = activeRequestId.incrementAndGet();
        env.info("LLM 开始处理: " + prompt + ", requestId=" + requestId);

        Thread streamThread = new Thread(() -> {
            FinishReason finishReason = FinishReason.FAILED;
            try {
                List<JsonObject> messages = new ArrayList<>();
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", prompt);
                messages.add(userMessage);

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "default");
                requestBody.add("messages", JsonParser.parseString(messages.toString()).getAsJsonArray());
                requestBody.addProperty("stream", true);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    onError.accept("LLM 服务返回异常状态码: " + response.statusCode());
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
                            env.error("解析 LLM 响应失败: " + line, e);
                        }
                    }
                }
            } catch (IOException e) {
                if (requestId != activeRequestId.get() || isExpectedCancellation(e)) {
                    finishReason = FinishReason.CANCELLED;
                } else {
                    env.error("LLM 请求发生真实网络异常", e);
                    onError.accept("LLM 网络异常: " + e.getMessage());
                    finishReason = FinishReason.FAILED;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (requestId != activeRequestId.get()) {
                    finishReason = FinishReason.CANCELLED;
                } else {
                    env.error("LLM 请求失败", e);
                    onError.accept("LLM 请求被中断");
                    finishReason = FinishReason.FAILED;
                }
            } finally {
                if (currentStream != null) {
                    try {
                        currentStream.close();
                    } catch (IOException e) {
                        env.error("关闭流失败", e);
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
            env.info("取消 LLM 生成");
            try {
                currentStream.close();
            } catch (IOException e) {
                env.error("关闭流失败", e);
            } finally {
                currentStream = null;
            }
        }
    }

    public void shutdown() {
        cancelGeneration();
        env.info("LLM 引擎已关闭");
    }
}
