package com.rheinmetal.tianshu.client.rag;

import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptLanguage;
import com.rheinmetal.tianshu.function.assistant.rag.DefaultRuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.assistant.rag.RuntimeFactTextResolver;
import net.minecraft.client.resources.language.I18n;

public final class MinecraftRuntimeFactTextResolver implements RuntimeFactTextResolver {
    private final RuntimeFactTextResolver fallback;

    public MinecraftRuntimeFactTextResolver() {
        this(DefaultRuntimeFactTextResolver.instance());
    }

    public MinecraftRuntimeFactTextResolver(RuntimeFactTextResolver fallback) {
        this.fallback = fallback == null ? DefaultRuntimeFactTextResolver.instance() : fallback;
    }

    @Override
    public String text(AssistantPromptLanguage language, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (I18n.exists(key)) {
            return I18n.get(key);
        }
        return fallback.text(language, key);
    }
}
