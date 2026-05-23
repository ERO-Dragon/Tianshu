package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;

import java.util.Map;

public interface RuntimeFactTextResolver {
    String text(AXPromptLanguage language, String key);

    default String format(AXPromptLanguage language, String key, Map<String, String> arguments) {
        String template = text(language, key);
        if (arguments == null || arguments.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{" + entry.getKey() + "}", value);
            result = result.replace("%" + entry.getKey() + "%", value);
        }
        return result;
    }
}
