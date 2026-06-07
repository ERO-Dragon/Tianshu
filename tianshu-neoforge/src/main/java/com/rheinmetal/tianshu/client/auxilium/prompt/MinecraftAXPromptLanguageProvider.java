package com.rheinmetal.tianshu.client.auxilium.prompt;

import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;

public final class MinecraftAXPromptLanguageProvider implements AXPromptLanguageProvider {
    @Override
    public AXPromptLanguage currentLanguage() {
        return ClientLanguagePolicy.currentPromptLanguage();
    }
}
