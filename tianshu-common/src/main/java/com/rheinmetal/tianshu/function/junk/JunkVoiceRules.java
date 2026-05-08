package com.rheinmetal.tianshu.function.junk;

public final class JunkVoiceRules {
    private static final JunkVoiceWords.Words WORDS = JunkVoiceWords.load();

    private JunkVoiceRules() {
    }

    public static boolean isClearIntent(String normalizedText) {
        return containsAny(normalizedText, "清空垃圾", "清理垃圾", "扔掉没用的", "丢掉没用的", "倒垃圾", "清垃圾");
    }

    public static JunkVoiceAction resolveMarkAction(String normalizedText) {
        if (containsAny(normalizedText, "先别扔", "不要扔", "别扔", "取消垃圾", "移出垃圾", "不当垃圾", "不是垃圾")) {
            return JunkVoiceAction.UNMARK;
        }
        if (containsAny(normalizedText, "当垃圾", "标记为垃圾", "设为垃圾", "加入垃圾", "以后都扔", "以后都丢")) {
            return JunkVoiceAction.MARK;
        }
        return JunkVoiceAction.NONE;
    }

    public static JunkVoiceWords.Words words() {
        return WORDS;
    }

    private static boolean containsAny(String text, String... fragments) {
        if (text == null || text.isBlank()) return false;
        for (String fragment : fragments) {
            if (text.contains(JunkTextNormalizer.normalize(fragment))) return true;
        }
        return false;
    }
}
