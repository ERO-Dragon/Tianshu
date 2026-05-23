package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;

import java.util.Map;

public final class DefaultRuntimeFactTextResolver implements RuntimeFactTextResolver {
    private static final DefaultRuntimeFactTextResolver INSTANCE = new DefaultRuntimeFactTextResolver();
    private static final Map<String, String> FALLBACK_TEXTS = Map.of(
            "tianshu.llm.rag.value.unknown", "unknown",
            "tianshu.llm.rag.dimension.unknown", "unknown",
            "tianshu.llm.rag.biome.unknown", "unknown",
            "tianshu.llm.rag.inventory.separator", ", ",
            "tianshu.llm.rag.chat.separator", "; "
    );

    public static DefaultRuntimeFactTextResolver instance() {
        return INSTANCE;
    }

    @Override
    public String text(AXPromptLanguage language, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return FALLBACK_TEXTS.getOrDefault(key, key);
    }
}
