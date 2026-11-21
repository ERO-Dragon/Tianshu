package com.rheinmetal.tianshu.auxilium.core.prompt;

public interface AXPromptContributor {
    String sectionId();

    void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder);
}
