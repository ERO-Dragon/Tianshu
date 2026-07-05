package com.rheinmetal.tianshu.function.llm.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LLMRequest {
    public static final int MIN_TASK_PRIORITY = 0;
    public static final int MAX_TASK_PRIORITY = 1000;


    private Integer maxTokens = 0;
    private Float temperature = null;
    private Integer topK = null;
    private Float topP = null;
    private Float minP = null;
    private Float penaltyRepeat = null;
    private Float penaltyFreq = null;
    private Float penaltyPresent = null;
    private Integer penaltyLastN = null;
    private Boolean stream = false;
    private Boolean thinking = false;
    private Boolean captureThinkingContent = false;
    private String toolsJson = "";

    private String lane = "CHAT";
    private Integer taskPriority = 0;
    private Boolean taskPreemptible = false;
    private LlmInferencePolicy inferencePolicy = LlmInferencePolicy.defaults();

    private List<Chunk> chunks = new ArrayList<>();

    public LLMRequest() {
    }

    public static LLMRequest of(Chunk... chunks) {
        LLMRequest request = new LLMRequest();
        request.chunks = chunks == null ? new ArrayList<>() : new ArrayList<>(List.of(chunks));
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

    public Integer getTopK() {
        return topK;
    }

    public Float getTopP() {
        return topP;
    }

    public Float getMinP() {
        return minP;
    }

    public Float getPenaltyRepeat() {
        return penaltyRepeat;
    }

    public Float getPenaltyFreq() {
        return penaltyFreq;
    }

    public Float getPenaltyPresent() {
        return penaltyPresent;
    }

    public Integer getPenaltyLastN() {
        return penaltyLastN;
    }

    public Boolean getStream() {
        return stream;
    }

    public Boolean getThinking() {
        return thinking;
    }

    public Boolean getCaptureThinkingContent() {
        return captureThinkingContent;
    }

    public String getToolsJson() {
        return toolsJson;
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

    public LlmInferencePolicy getInferencePolicy() {
        return inferencePolicy;
    }

    public List<Chunk> getChunks() {
        return chunks;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens != null ? maxTokens : 0;
    }

    public void setTemperature(Float temperature) {
        if (temperature == null || Float.isNaN(temperature) || temperature < 0f || temperature > 2f) {
            this.temperature = null;
        } else {
            this.temperature = temperature;
        }
    }

    public void setTopK(Integer topK) {
        this.topK = topK != null && topK > 0 ? topK : null;
    }

    public void setTopP(Float topP) {
        this.topP = normalizeUnit(topP);
    }

    public void setMinP(Float minP) {
        this.minP = normalizeUnit(minP);
    }

    public void setPenaltyRepeat(Float penaltyRepeat) {
        this.penaltyRepeat = normalizePenalty(penaltyRepeat);
    }

    public void setPenaltyFreq(Float penaltyFreq) {
        this.penaltyFreq = normalizePenalty(penaltyFreq);
    }

    public void setPenaltyPresent(Float penaltyPresent) {
        this.penaltyPresent = normalizePenalty(penaltyPresent);
    }

    public void setPenaltyLastN(Integer penaltyLastN) {
        this.penaltyLastN = penaltyLastN != null && penaltyLastN >= 0 ? penaltyLastN : null;
    }

    public void setStream(Boolean stream) {
        this.stream = stream != null ? stream : false;
    }

    public void setThinking(Boolean thinking) {
        this.thinking = thinking != null ? thinking : false;
    }

    public void setCaptureThinkingContent(Boolean captureThinkingContent) {
        this.captureThinkingContent = captureThinkingContent != null ? captureThinkingContent : false;
    }

    public void setToolsJson(String toolsJson) {
        this.toolsJson = toolsJson == null ? "" : toolsJson.trim();
    }

    public void setLane(String lane) {
        this.lane = lane != null ? lane : "CHAT";
    }

    public void setTaskPriority(Integer taskPriority) {
        this.taskPriority = taskPriority != null ? clamp(taskPriority, MIN_TASK_PRIORITY, MAX_TASK_PRIORITY) : MIN_TASK_PRIORITY;
    }

    public void setTaskPreemptible(Boolean taskPreemptible) {
        this.taskPreemptible = taskPreemptible != null ? taskPreemptible : false;
    }

    public void setInferencePolicy(LlmInferencePolicy inferencePolicy) {
        this.inferencePolicy = inferencePolicy == null ? LlmInferencePolicy.defaults() : inferencePolicy;
    }

    public void setChunks(List<Chunk> chunks) {
        this.chunks = chunks != null ? new ArrayList<>(chunks) : new ArrayList<>();
    }

    public void addChunk(Chunk chunk) {
        if (chunk != null) {
            this.chunks.add(chunk);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Float normalizeUnit(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 1f) {
            return null;
        }
        return value;
    }

    private Float normalizePenalty(Float value) {
        if (value == null || Float.isNaN(value) || value < 0f || value > 4f) {
            return null;
        }
        return value;
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
