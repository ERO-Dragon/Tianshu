package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheGameContextKnowledgePlanner;
import com.rheinmetal.tianshu.dubhe.dialogue.currentinput.DubheCurrentInputPromptContributor;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheGameContextPromptContributor;
import com.rheinmetal.tianshu.dubhe.module.memory.DubhePlayerMemoryPromptContributor;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryPromptContributor;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialoguePromptContributor;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubheSystemPromptContributor;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptProfile;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptRequest;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptResourceRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptTask;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptTexts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class DubhePromptOrchestrator {
    private final DubhePromptResourceRepository resourceRepository;
    private final DubhePromptLanguageProvider languageProvider;
    private final List<DubhePromptContributor> contributors;

    public DubhePromptOrchestrator(
            DubhePromptResourceRepository resourceRepository,
            DubhePromptLanguageProvider languageProvider,
            List<DubhePromptContributor> contributors
    ) {
        this(resourceRepository, languageProvider, DubheGameContextKnowledgePlanner.NONE, contributors);
    }

    public DubhePromptOrchestrator(
            DubhePromptResourceRepository resourceRepository,
            DubhePromptLanguageProvider languageProvider,
            DubheGameContextKnowledgePlanner knowledgePlanner,
            List<DubhePromptContributor> contributors
    ) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? DubhePromptLanguageProvider.fixed(DubhePromptLanguage.EN_US) : languageProvider;
        DubheGameContextKnowledgePlanner effectivePlanner = knowledgePlanner == null ? DubheGameContextKnowledgePlanner.NONE : knowledgePlanner;
        this.contributors = contributors == null ? defaultContributors(effectivePlanner) : List.copyOf(contributors);
    }

    public static List<DubhePromptContributor> defaultContributors() {
        return defaultContributors(DubheGameContextKnowledgePlanner.NONE);
    }

    public static List<DubhePromptContributor> defaultContributors(DubheGameContextKnowledgePlanner knowledgePlanner) {
        return List.of(
                new DubheSystemPromptContributor(),
                new DubheMemorySummaryPromptContributor(),
                new DubheGameContextPromptContributor(knowledgePlanner),
                new DubhePlayerMemoryPromptContributor(),
                new DubheRecentDialoguePromptContributor(),
                new DubheCurrentInputPromptContributor()
        );
    }

    public DubhePromptAssembly assemble(DubheRequest request, DubheContextSnapshot context, DubheContextBudget budget) {
        DubhePromptLanguage language = languageProvider.currentLanguage();
        DubhePromptProfile profile = loadProfile(language);
        DubhePromptTexts texts = loadTexts(language);
        DubhePromptBuildContext buildContext = new DubhePromptBuildContext(request, context, budget, language, profile, texts);
        DubhePromptAssemblyBuilder builder = new DubhePromptAssemblyBuilder();
        for (DubhePromptContributor contributor : orderedContributors(profile)) {
            if (contributor != null) {
                contributor.contribute(buildContext, builder);
            }
        }
        return builder.build();
    }

    private List<DubhePromptContributor> orderedContributors(DubhePromptProfile profile) {
        if (contributors.isEmpty() || profile == null || profile.sectionOrder().isEmpty()) {
            return contributors;
        }
        List<DubhePromptContributor> ordered = new ArrayList<>();
        LinkedHashSet<DubhePromptContributor> seen = new LinkedHashSet<>();
        for (String sectionId : profile.sectionOrder()) {
            String normalized = normalizeSectionId(sectionId);
            if (normalized.isBlank()) {
                continue;
            }
            for (DubhePromptContributor contributor : contributors) {
                if (contributor != null
                        && !seen.contains(contributor)
                        && normalized.equals(normalizeSectionId(contributor.sectionId()))) {
                    ordered.add(contributor);
                    seen.add(contributor);
                }
            }
        }
        for (DubhePromptContributor contributor : contributors) {
            if (contributor != null && seen.add(contributor)) {
                ordered.add(contributor);
            }
        }
        return List.copyOf(ordered);
    }

    private String normalizeSectionId(String sectionId) {
        return sectionId == null ? "" : sectionId.trim().toLowerCase(Locale.ROOT);
    }

    private DubhePromptProfile loadProfile(DubhePromptLanguage language) {
        if (resourceRepository == null) {
            return DubhePromptProfile.defaultFor(DubhePromptTask.GENERAL_DUBHE, language);
        }
        return resourceRepository.loadProfile(DubhePromptTask.GENERAL_DUBHE, language, DubhePromptRequest.general(language).variant());
    }

    private DubhePromptTexts loadTexts(DubhePromptLanguage language) {
        if (resourceRepository == null) {
            return DubhePromptTexts.builtin(language);
        }
        return resourceRepository.loadTexts(language);
    }
}
