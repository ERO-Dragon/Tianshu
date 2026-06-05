package com.rheinmetal.tianshu.function.llm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LLMRequest {

    private Integer maxTokens = 0;
    private Float temperature = 0.7f;
    private Boolean stream = false;
    private Boolean thinking = false;

    private String lane = "CHAT";
    private Integer taskPriority = 0;
    private Boolean taskPreemptible = false;

    private List<Chunk> chunks = new ArrayList<>();

    public LLMRequest() {
    }

    public static LLMRequest of(Chunk... chunks) {
        LLMRequest request = new LLMRequest();
        request.chunks = List.of(chunks);
        return request;
    }

    public static LLMRequest ofMessage(MessageItem... items) {
        return of(Chunk.message(items));
    }

    public static LLMRequest ofMessage(List<MessageItem> items) {
        return of(Chunk.message(items));
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public Float getTemperature() {
        return temperature;
    }

    public Boolean getStream() {
        return stream;
    }

    public Boolean getThinking() {
        return thinking;
    }

    public String getLane() {
        return lane;
    }

    public Integer getTaskPriority() {
        return taskPriority;
    }

    public Boolean getTaskPreemptible() {
        return taskPreemptible;
    }

    public List<Chunk> getChunks() {
        return chunks;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens != null ? maxTokens : 0;
    }

    public void setTemperature(Float temperature) {
        if (temperature == null || Float.isNaN(temperature) || temperature < 0f || temperature > 2f) {
            this.temperature = 0.7f;
        } else {
            this.temperature = temperature;
        }
    }

    public void setStream(Boolean stream) {
        this.stream = stream != null ? stream : false;
    }

    public void setThinking(Boolean thinking) {
        this.thinking = thinking != null ? thinking : false;
    }

    public void setLane(String lane) {
        this.lane = lane != null ? lane : "CHAT";
    }

    public void setTaskPriority(Integer taskPriority) {
        this.taskPriority = taskPriority != null ? clamp(taskPriority, -1000, 1000) : 0;
    }

    public void setTaskPreemptible(Boolean taskPreemptible) {
        this.taskPreemptible = taskPreemptible != null ? taskPreemptible : false;
    }

    public void setChunks(List<Chunk> chunks) {
        this.chunks = chunks != null ? chunks : new ArrayList<>();
    }

    public void addChunk(Chunk chunk) {
        if (chunk != null) {
            this.chunks.add(chunk);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isTaskLane() {
        return "TASK".equalsIgnoreCase(lane);
    }

    public List<MessageItem> extractMessages() {
        return chunks.stream()
                .filter(chunk -> "message".equalsIgnoreCase(chunk.getType()))
                .flatMap(chunk -> chunk.getMessageContent() != null ? chunk.getMessageContent().stream() : Stream.empty())
                .collect(Collectors.toList());
    }

    public List<Chunk> extractRagChunks() {
        return chunks.stream()
                .filter(chunk -> "rag".equalsIgnoreCase(chunk.getType()))
                .collect(Collectors.toList());
    }
}
