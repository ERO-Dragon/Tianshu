package com.rheinmetal.tianshu.client.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.rag.DefaultRuntimeFactTextResolver;
import com.rheinmetal.tianshu.function.auxilium.rag.RuntimeFactTextResolver;
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
    public String text(AXPromptLanguage language, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (I18n.exists(key)) {
            return I18n.get(key);
        }
        return fallback.text(language, key);
    }
}
