package com.rheinmetal.tianshu.function.llm.service;

public class MessageItem {

    private String role;
    private String content;

    public MessageItem() {
    }

    public MessageItem(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static MessageItem of(String role, String content) {
        return new MessageItem(role, content);
    }

    public static MessageItem system(String content) {
        return new MessageItem("system", content);
    }

    public static MessageItem user(String content) {
        return new MessageItem("user", content);
    }

    public static MessageItem assistant(String content) {
        return new MessageItem("assistant", content);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role != null ? role : "user";
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content != null ? content : "";
    }
}