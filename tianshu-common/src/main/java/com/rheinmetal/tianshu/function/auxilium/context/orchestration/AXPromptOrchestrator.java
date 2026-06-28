package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.knowledge.AXStaticKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptRequest;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTask;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;

import java.util.List;

public final class AXPromptOrchestrator {
    private final AXPromptResourceRepository resourceRepository;
    private final AXPromptLanguageProvider languageProvider;
    private final List<AXPromptContributor> contributors;

    public AXPromptOrchestrator(
            AXPromptResourceRepository resourceRepository,
            AXPromptLanguageProvider languageProvider,
            List<AXPromptContributor> contributors
    ) {
        this(resourceRepository, languageProvider, AXStaticKnowledgePlanner.NONE, contributors);
    }

    public AXPromptOrchestrator(
            AXPromptResourceRepository resourceRepository,
            AXPromptLanguageProvider languageProvider,
            AXStaticKnowledgePlanner staticKnowledgePlanner,
            List<AXPromptContributor> contributors
    ) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
        AXStaticKnowledgePlanner effectivePlanner = staticKnowledgePlanner == null ? AXStaticKnowledgePlanner.NONE : staticKnowledgePlanner;
        this.contributors = contributors == null ? defaultContributors(effectivePlanner) : List.copyOf(contributors);
    }

    public static List<AXPromptContributor> defaultContributors() {
        return defaultContributors(AXStaticKnowledgePlanner.NONE);
    }

    public static List<AXPromptContributor> defaultContributors(AXStaticKnowledgePlanner staticKnowledgePlanner) {
        return List.of(
                new AXSystemPromptContributor(),
                new AXGameContextPromptContributor(staticKnowledgePlanner),
                new AXPlayerMemoryPromptContributor(),
                new AXProvidedContextPromptContributor(),
                new AXRecentDialoguePromptContributor(),
                new AXCurrentInputPromptContributor()
        );
    }

    public AXPromptAssembly assemble(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
        AXPromptLanguage language = languageProvider.currentLanguage();
        AXPromptProfile profile = loadProfile(language);
        AXPromptTexts texts = loadTexts(language);
        AXPromptBuildContext buildContext = new AXPromptBuildContext(request, context, budget, language, profile, texts);
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();
        for (AXPromptContributor contributor : contributors) {
            if (contributor != null) {
                contributor.contribute(buildContext, builder);
            }
        }
        return builder.build();
    }

    private AXPromptProfile loadProfile(AXPromptLanguage language) {
        if (resourceRepository == null) {
            return AXPromptProfile.defaultFor(AXPromptTask.GENERAL_AX, language);
        }
        return resourceRepository.loadProfile(AXPromptTask.GENERAL_AX, language, AXPromptRequest.general(language).variant());
    }

    private AXPromptTexts loadTexts(AXPromptLanguage language) {
        if (resourceRepository == null) {
            return AXPromptTexts.builtin(language);
        }
        return resourceRepository.loadTexts(language);
    }
}
