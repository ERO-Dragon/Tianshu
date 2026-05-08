package com.rheinmetal.tianshu.function.chatassistant;

import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;

import java.util.List;
import java.util.Locale;

public final class ChatAssistantCommand {
    public enum Action {
        OPEN,
        SEND,
        CANCEL,
        RETRY,
        APPEND,
        IGNORE
    }

    private final Action action;
    private final String text;

    private ChatAssistantCommand(Action action, String text) {
        this.action = action == null ? Action.IGNORE : action;
        this.text = normalizeText(text);
    }

    public Action action() {
        return action;
    }

    public String text() {
        return text;
    }

    public static ChatAssistantCommand parse(VoiceTriggerPayload payload, boolean inputActive) {
        if (payload == null) {
            return new ChatAssistantCommand(Action.IGNORE, "");
        }
        if (isOpenIntent(payload)) {
            return new ChatAssistantCommand(Action.OPEN, "");
        }
        if (containsWord(payload.matchedExtraWords(), "发送") || containsWord(payload.matchedExtraWords(), "确认")) {
            return new ChatAssistantCommand(Action.SEND, "");
        }
        if (containsWord(payload.matchedExtraWords(), "取消")) {
            return new ChatAssistantCommand(Action.CANCEL, "");
        }
        if (containsWord(payload.matchedExtraWords(), "重来")) {
            return new ChatAssistantCommand(Action.RETRY, "");
        }
        if (!inputActive) {
            return new ChatAssistantCommand(Action.IGNORE, "");
        }
        String text = normalizeText(payload.sourceText());
        if (text.isEmpty()) {
            return new ChatAssistantCommand(Action.IGNORE, "");
        }
        return new ChatAssistantCommand(Action.APPEND, text);
    }

    private static boolean isOpenIntent(VoiceTriggerPayload payload) {
        return containsWord(payload.matchedHotwords(), "发送消息")
                || containsWord(payload.matchedHotwords(), "聊天")
                || containsWord(payload.matchedHotwords(), "打开聊天")
                || containsWord(payload.matchedHotwords(), "语音发送");
    }

    private static boolean containsWord(List<String> words, String expected) {
        if (words == null || words.isEmpty()) {
            return false;
        }
        String normalizedExpected = normalizeText(expected).toLowerCase(Locale.ROOT);
        if (normalizedExpected.isEmpty()) {
            return false;
        }
        for (String word : words) {
            String normalizedWord = normalizeText(word).toLowerCase(Locale.ROOT);
            if (normalizedWord.contains(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
