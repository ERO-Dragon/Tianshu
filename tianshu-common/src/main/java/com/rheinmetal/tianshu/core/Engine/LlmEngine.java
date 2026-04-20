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
import java.util.function.Consumer;

public class LlmEngine {
    private final IGameEnvironment env;
    private HttpClient httpClient;
    private String baseUrl;
    private InputStream currentStream;

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

    public void streamChat(String prompt, Consumer<String> onChunk, Runnable onComplete) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            env.error("LLM 引擎未初始化，baseUrl 为空", null);
            return;
        }

        env.info("LLM 开始处理: " + prompt);

        Thread streamThread = new Thread(() -> {
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
                currentStream = response.body();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (data.equals("[DONE]")) {
                                env.info("LLM 处理完成");
                                onComplete.run();
                                break;
                            }
                            try {
                                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                                if (chunk.has("choices")) {
                                    JsonArray choices = chunk.getAsJsonArray("choices");
                                    if (choices == null || choices.isEmpty()) {
                                        return; // 这是 UsageChunk 或异常响应，没有生成内容，直接跳过
                                    }
                                    JsonObject choice = choices.get(0).getAsJsonObject();
                                    if (choice.has("delta")) {
                                        JsonObject delta = choice.getAsJsonObject("delta");
                                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                            String content = delta.get("content").getAsString();
                                            if (!content.isEmpty()) {
                                                onChunk.accept(content);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                env.error("解析 LLM 响应失败: " + line, e);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (e.getMessage() != null &&
                    (e.getMessage().contains("closed") ||
                     e.getMessage().contains("subscription cancelled") ||
                     e.getMessage().contains("chunked transfer encoding"))) {
                    env.info("LLM 流被主动打断（预期行为），忽略异常");
                } else {
                    env.error("LLM 请求发生真实网络异常", e);
                }
            } catch (InterruptedException e) {
                if (Thread.currentThread().isInterrupted()) {
                    env.info("LLM 请求被中断");
                } else {
                    env.error("LLM 请求失败", e);
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
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();
    }

    public void cancelGeneration() {
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
