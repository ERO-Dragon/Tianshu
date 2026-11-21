package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

public interface DubhePromptContributor {
    String sectionId();

    void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder);
}
