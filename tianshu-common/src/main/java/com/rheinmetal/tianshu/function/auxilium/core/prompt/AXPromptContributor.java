package com.rheinmetal.tianshu.function.auxilium.core.prompt;

public interface AXPromptContributor {
    String sectionId();

    void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder);
}
