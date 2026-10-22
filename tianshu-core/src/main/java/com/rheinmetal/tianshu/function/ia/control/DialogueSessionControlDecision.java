package com.rheinmetal.tianshu.function.ia.control;

public record DialogueSessionControlDecision(boolean allowed, String reasonCode, String message) {
    public DialogueSessionControlDecision {
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        message = message == null ? "" : message.trim();
    }

    public static DialogueSessionControlDecision allow() {
        return new DialogueSessionControlDecision(true, "", "");
    }

    public static DialogueSessionControlDecision deny(String reasonCode, String message) {
        return new DialogueSessionControlDecision(false, reasonCode, message);
    }
}
