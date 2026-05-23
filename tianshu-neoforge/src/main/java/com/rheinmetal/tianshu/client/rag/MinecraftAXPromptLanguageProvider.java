package com.rheinmetal.tianshu.client.rag;

import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;

public final class MinecraftAXPromptLanguageProvider implements AXPromptLanguageProvider {
    @Override
    public AXPromptLanguage currentLanguage() {
        return ClientLanguagePolicy.currentPromptLanguage();
    }
}
