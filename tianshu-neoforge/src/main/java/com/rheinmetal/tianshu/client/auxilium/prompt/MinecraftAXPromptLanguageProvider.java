package com.rheinmetal.tianshu.client.auxilium.prompt;

import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;

public final class MinecraftAXPromptLanguageProvider implements AXPromptLanguageProvider {
    @Override
    public AXPromptLanguage currentLanguage() {
        return ClientLanguagePolicy.currentPromptLanguage();
    }
}
