package com.rheinmetal.tianshu.function.ia.security;

public record DialogueAccessDecision(boolean allowed, String reasonCode, String message) {
    public DialogueAccessDecision {
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        message = message == null ? "" : message.trim();
    }

    public static DialogueAccessDecision allow() {
        return new DialogueAccessDecision(true, "", "");
    }

    public static DialogueAccessDecision deny(String reasonCode, String message) {
        return new DialogueAccessDecision(false, reasonCode, message);
    }
}
