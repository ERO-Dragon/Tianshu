package com.rheinmetal.tianshu.function.assistant.prompt;

public enum AssistantPromptLanguage {
    ZH_CN("zh_cn"),
    EN_US("en_us");

    private final String code;

    AssistantPromptLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AssistantPromptLanguage fromText(String text) {
        if (text == null || text.isBlank()) {
            return EN_US;
        }
        int cjk = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                cjk++;
            } else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                latin++;
            }
        }
        return latin > cjk * 2 ? EN_US : ZH_CN;
    }
}
