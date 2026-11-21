package com.rheinmetal.tianshu.dubhe.core.prompt;

public interface DubhePromptContributor {
    String sectionId();

    void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder);
}
