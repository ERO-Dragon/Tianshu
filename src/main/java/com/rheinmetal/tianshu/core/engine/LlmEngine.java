package com.rheinmetal.tianshu.core.engine;

import com.rheinmetal.tianshu.Tianshu;
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
    private HttpClient httpClient;
    private String baseUrl;
    private InputStream currentStream;

    public LlmEngine(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public void initialize(String baseUrl) {
        this.baseUrl = baseUrl;
        Tianshu.LOGGER.info("初始化 LLM 引擎，baseUrl: {}", baseUrl);
    }

    public void streamChat(String prompt, Consumer<String> onChunk, Runnable onComplete) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            Tianshu.LOGGER.error("LLM 引擎未初始化，baseUrl 为空");
            return;
        }

        Tianshu.LOGGER.info("LLM 开始处理: {}", prompt);

        Thread streamThread = new Thread(() -> {
            try {
                // 构建 OpenAI 兼容的 JSON 请求体
                List<JsonObject> messages = new ArrayList<>();
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", prompt);
                messages.add(userMessage);

                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "default");
                requestBody.add("messages", JsonParser.parseString(messages.toString()).getAsJsonArray());
                requestBody.addProperty("stream", true);

                // 构建 HTTP 请求
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();

                // 发送请求并获取响应流
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                currentStream = response.body();

                // 逐行读取 SSE 流
                try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(currentStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (data.equals("[DONE]")) {
                                Tianshu.LOGGER.info("LLM 处理完成");
                                onComplete.run();
                                break;
                            }
                            try {
                                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                                if (chunk.has("choices")) {
                                    JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();
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
                                Tianshu.LOGGER.error("解析 LLM 响应失败: {}", line, e);
                            }
                        }
                    }
                }
                } catch (IOException e) {
                    // 【关键修复】判断如果是主动 cancel 导致的流关闭，就当没看见，不打 ERROR 堆栈
                    if (e.getMessage() != null && 
                        (e.getMessage().contains("closed") || 
                         e.getMessage().contains("subscription cancelled") || 
                         e.getMessage().contains("chunked transfer encoding"))) {
                        
                        Tianshu.LOGGER.debug("LLM 流被主动打断（预期行为），忽略异常");
                    } else {
                        // 真正的网络断开、服务挂了等未知错误，才打 ERROR
                        Tianshu.LOGGER.error("LLM 请求发生真实网络异常", e);
                    }
                } catch (InterruptedException e) {
                // 捕获中断异常，实现取消功能
                if (Thread.currentThread().isInterrupted()) {
                    Tianshu.LOGGER.info("LLM 请求被中断");
                } else {
                    Tianshu.LOGGER.error("LLM 请求失败", e);
                }
            } finally {
                // 确保流被关闭
                if (currentStream != null) {
                    try {
                        currentStream.close();
                    } catch (IOException e) {
                        Tianshu.LOGGER.error("关闭流失败", e);
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
            Tianshu.LOGGER.info("取消 LLM 生成");
            try {
                currentStream.close();
            } catch (IOException e) {
                Tianshu.LOGGER.error("关闭流失败", e);
            } finally {
                currentStream = null;
            }
        }
    }

    public void shutdown() {
        cancelGeneration();
        Tianshu.LOGGER.info("LLM 引擎已关闭");
    }
}
