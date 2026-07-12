package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXGameContextKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.module.currentinput.AXCurrentInputPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXGameContextPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXPlayerMemoryPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialoguePromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXSystemPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptProfile;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptRequest;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTask;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class AXPromptOrchestrator {
    private final AXPromptResourceRepository resourceRepository;
    private final AXPromptLanguageProvider languageProvider;
    private final List<AXPromptContributor> contributors;
    private final AXTokenCounter tokenCounter;

    public AXPromptOrchestrator(
            AXPromptResourceRepository resourceRepository,
            AXPromptLanguageProvider languageProvider,
            List<AXPromptContributor> contributors
    ) {
        this(resourceRepository, languageProvider, AXGameContextKnowledgePlanner.NONE, contributors);
    }

    public AXPromptOrchestrator(
            AXPromptResourceRepository resourceRepository,
            AXPromptLanguageProvider languageProvider,
            AXGameContextKnowledgePlanner knowledgePlanner,
            List<AXPromptContributor> contributors
    ) {
        this(resourceRepository, languageProvider, knowledgePlanner, contributors, null);
    }

    public AXPromptOrchestrator(
            AXPromptResourceRepository resourceRepository,
            AXPromptLanguageProvider languageProvider,
            AXGameContextKnowledgePlanner knowledgePlanner,
            List<AXPromptContributor> contributors,
            AXTokenCounter tokenCounter
    ) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
        AXGameContextKnowledgePlanner effectivePlanner = knowledgePlanner == null ? AXGameContextKnowledgePlanner.NONE : knowledgePlanner;
        this.contributors = contributors == null ? defaultContributors(effectivePlanner) : List.copyOf(contributors);
        this.tokenCounter = tokenCounter == null ? AXTokenCounter.unavailable() : tokenCounter;
    }

    public static List<AXPromptContributor> defaultContributors() {
        return defaultContributors(AXGameContextKnowledgePlanner.NONE);
    }

    public static List<AXPromptContributor> defaultContributors(AXGameContextKnowledgePlanner knowledgePlanner) {
        return List.of(
                new AXSystemPromptContributor(),
                new AXGameContextPromptContributor(knowledgePlanner),
                new AXPlayerMemoryPromptContributor(),
                new AXRecentDialoguePromptContributor(),
                new AXCurrentInputPromptContributor()
        );
    }

    public AXPromptAssembly assemble(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
        AXPromptLanguage language = languageProvider.currentLanguage();
        AXPromptProfile profile = loadProfile(language);
        AXPromptTexts texts = loadTexts(language);
        AXPromptBuildContext buildContext = new AXPromptBuildContext(request, context, budget, language, profile, texts, tokenCounter);
        AXPromptAssemblyBuilder builder = new AXPromptAssemblyBuilder();
        for (AXPromptContributor contributor : orderedContributors(profile)) {
            if (contributor != null) {
                contributor.contribute(buildContext, builder);
            }
        }
        return builder.build();
    }

    private List<AXPromptContributor> orderedContributors(AXPromptProfile profile) {
        if (contributors.isEmpty() || profile == null || profile.sectionOrder().isEmpty()) {
            return contributors;
        }
        List<AXPromptContributor> ordered = new ArrayList<>();
        LinkedHashSet<AXPromptContributor> seen = new LinkedHashSet<>();
        for (String sectionId : profile.sectionOrder()) {
            String normalized = normalizeSectionId(sectionId);
            if (normalized.isBlank()) {
                continue;
            }
            for (AXPromptContributor contributor : contributors) {
                if (contributor != null
                        && !seen.contains(contributor)
                        && normalized.equals(normalizeSectionId(contributor.sectionId()))) {
                    ordered.add(contributor);
                    seen.add(contributor);
                }
            }
        }
        for (AXPromptContributor contributor : contributors) {
            if (contributor != null && seen.add(contributor)) {
                ordered.add(contributor);
            }
        }
        return List.copyOf(ordered);
    }

    private String normalizeSectionId(String sectionId) {
        return sectionId == null ? "" : sectionId.trim().toLowerCase(Locale.ROOT);
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
