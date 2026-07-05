package com.rheinmetal.tianshu.function.llm.service;

import java.util.ArrayList;
import java.util.List;

public class Chunk {

    private String type;

    private List<MessageItem> messageContent;
    private List<String> ragContent;

    private String uid;
    private String prompt = "";
    private Boolean useCache = true;
    private Boolean includeRagHits = true;
    private Integer memoryRagTokenBudget = 1000;

    public Chunk() {
    }

    public static Chunk message(MessageItem... items) {
        Chunk chunk = new Chunk();
        chunk.setType("message");
        chunk.setMessageContent(items != null ? List.of(items) : new ArrayList<>());
        return chunk;
    }

    public static Chunk message(List<MessageItem> items) {
        Chunk chunk = new Chunk();
        chunk.setType("message");
        chunk.setMessageContent(items);
        return chunk;
    }

    public static Chunk rag(String uid, List<String> contents) {
        return rag(uid, "", contents, true, true, 1000);
    }

    public static Chunk rag(String uid, String prompt, List<String> contents, boolean useCache, boolean includeRagHits, int memoryRagTokenBudget) {
        Chunk chunk = new Chunk();
        chunk.setType("rag");
        chunk.setUid(uid);
        chunk.setPrompt(prompt);
        chunk.setRagContent(contents);
        chunk.setUseCache(useCache);
        chunk.setIncludeRagHits(includeRagHits);
        chunk.setMemoryRagTokenBudget(memoryRagTokenBudget);
        return chunk;
    }

    // Getters
    public String getType() {
        return type;
    }

    public List<MessageItem> getMessageContent() {
        return messageContent;
    }

    public List<String> getRagContent() {
        return ragContent;
    }

    public String getUid() {
        return uid;
    }

    public String getPrompt() {
        return prompt;
    }

    public Boolean getUseCache() {
        return useCache;
    }

    public Boolean getIncludeRagHits() {
        return includeRagHits;
    }

    public Integer getMemoryRagTokenBudget() {
        return memoryRagTokenBudget;
    }

    // Setters
    public void setType(String type) {
        this.type = type;
    }

    public void setMessageContent(List<MessageItem> messageContent) {
        this.messageContent = messageContent;
    }

    public void setRagContent(List<String> ragContent) {
        this.ragContent = ragContent;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt != null ? prompt : "";
    }

    public void setUseCache(Boolean useCache) {
        this.useCache = useCache != null ? useCache : true;
    }

    public void setIncludeRagHits(Boolean includeRagHits) {
        this.includeRagHits = includeRagHits != null ? includeRagHits : true;
    }

    public void setMemoryRagTokenBudget(Integer memoryRagTokenBudget) {
        this.memoryRagTokenBudget = memoryRagTokenBudget != null ? memoryRagTokenBudget : 1000;
    }
}
