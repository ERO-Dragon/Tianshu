package com.rheinmetal.tianshu.function.assistant.rag;

import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;

import java.util.Map;

public interface RuntimeFactTextResolver {
    String text(AssistantPromptLanguage language, String key);

    default String format(AssistantPromptLanguage language, String key, Map<String, String> arguments) {
        String template = text(language, key);
        if (arguments == null || arguments.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
