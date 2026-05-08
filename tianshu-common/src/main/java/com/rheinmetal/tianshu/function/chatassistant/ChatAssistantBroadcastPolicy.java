package com.rheinmetal.tianshu.function.chatassistant;

import com.rheinmetal.tianshu.protocol.payload.ChatAssistantIncomingChatPayload;

import java.util.Locale;

public final class ChatAssistantBroadcastPolicy {
    private static final int MAX_SPOKEN_LENGTH = 120;
    private static final double CHINESE_RATIO_THRESHOLD = 0.35D;

    public Decision decide(ChatAssistantIncomingChatPayload payload) {
        if (payload == null || payload.messageText().isBlank()) {
            return Decision.ignore();
        }
        if (isSelfMessage(payload)) {
            return Decision.ignore();
        }
        String messageText = payload.messageText();
        LanguageKind messageLanguage = detectLanguage(messageText);
        LanguageKind localLanguage = resolveLocalLanguage(payload.localLanguageCode());
        if (messageLanguage == localLanguage) {
            return Decision.speak(truncate(messageText));
        }
        return Decision.ignore();
    }

    private static boolean isSelfMessage(ChatAssistantIncomingChatPayload payload) {
        if (payload.senderName().isBlank() || payload.localPlayerName().isBlank()) {
            return false;
        }
        return payload.senderName().equalsIgnoreCase(payload.localPlayerName());
    }

    private static LanguageKind resolveLocalLanguage(String languageCode) {
        String normalized = languageCode == null ? "" : languageCode.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("zh")) {
            return LanguageKind.CHINESE;
        }
        return LanguageKind.ENGLISH;
    }

    private static LanguageKind detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return LanguageKind.UNKNOWN;
        }
        int chinese = 0;
        int latin = 0;
        int cyrillic = 0;
        int meaningful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                chinese++;
                meaningful++;
            } else if (isLatin(c)) {
                latin++;
                meaningful++;
            } else if (isCyrillic(c)) {
                cyrillic++;
                meaningful++;
            }
        }
        if (meaningful == 0) {
            return LanguageKind.UNKNOWN;
        }
        if (chinese / (double) meaningful >= CHINESE_RATIO_THRESHOLD) {
            return LanguageKind.CHINESE;
        }
        int languageKinds = 0;
        if (chinese > 0) languageKinds++;
        if (latin > 0) languageKinds++;
        if (cyrillic > 0) languageKinds++;
        if (languageKinds > 1) {
            return LanguageKind.MIXED;
        }
        if (cyrillic > 0) {
            return LanguageKind.RUSSIAN;
        }
        if (latin > 0) {
            return LanguageKind.ENGLISH;
        }
        return LanguageKind.UNKNOWN;
    }

    private static boolean isChinese(char c) {
        return c >= '\u4E00' && c <= '\u9FFF';
    }

    private static boolean isLatin(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isCyrillic(char c) {
        return c >= '\u0400' && c <= '\u04FF';
    }

    private static String truncate(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_SPOKEN_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SPOKEN_LENGTH) + "…";
    }

    private enum LanguageKind {
        CHINESE,
        ENGLISH,
        RUSSIAN,
        MIXED,
        UNKNOWN
    }

    public record Decision(Action action, String text) {
        public enum Action {
            SPEAK,
            ALERT_ONLY,
            IGNORE
        }

        public Decision {
            if (action == null) action = Action.IGNORE;
            if (text == null) text = "";
        }

        public static Decision speak(String text) {
            return new Decision(Action.SPEAK, text);
        }

        public static Decision alertOnly(String text) {
            return new Decision(Action.ALERT_ONLY, text);
        }

        public static Decision ignore() {
            return new Decision(Action.IGNORE, "");
        }
    }
}
