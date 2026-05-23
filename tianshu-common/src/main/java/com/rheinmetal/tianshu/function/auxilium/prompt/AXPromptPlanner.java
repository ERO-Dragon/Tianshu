package com.rheinmetal.tianshu.function.auxilium.prompt;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.ShortTermMemoryBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AXPromptPlanner {
    private final AXPromptResourceRepository resourceRepository;
    private final AXPromptLanguageProvider languageProvider;

    public AXPromptPlanner() {
        this(null, null);
    }

    public AXPromptPlanner(AXPromptResourceRepository resourceRepository) {
        this(resourceRepository, null);
    }

    public AXPromptPlanner(AXPromptResourceRepository resourceRepository, AXPromptLanguageProvider languageProvider) {
        this.resourceRepository = resourceRepository;
        this.languageProvider = languageProvider == null ? AXPromptLanguageProvider.fixed(AXPromptLanguage.EN_US) : languageProvider;
    }

    public AXPromptPlan plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
        return plan(request, context, budget, AXPromptRequest.general(languageProvider.currentLanguage()));
    }

    public AXPromptPlan plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget, AXPromptRequest promptRequest) {
        AXPromptLanguage language = promptRequest == null ? languageProvider.currentLanguage() : promptRequest.language();
        AXPromptTask task = promptRequest == null ? AXPromptTask.GENERAL_AX : promptRequest.task();
        String variant = promptRequest == null ? "default" : promptRequest.variant();
        AXPromptProfile profile = resourceRepository == null
                ? AXPromptProfile.defaultFor(task, language)
                : resourceRepository.loadProfile(task, language, variant);
        List<AXPromptSection> sections = new ArrayList<>();
        sections.add(new AXPromptSection("identity", title(language, "身份", "Identity"), profile.identity(), 100));
        sections.add(new AXPromptSection("rules", title(language, "行为规则", "Behavior Rules"), profile.behaviorRules(), 98));
        if (context == null) {
            return new AXPromptPlan(profile, sections);
        }
        sections.add(new AXPromptSection("persona", title(language, "助手人设", "Persona"), context.memory().persona(), 95));
        sections.add(new AXPromptSection("scope", title(language, "作用域", "Scope"), scopeText(context, language), 92));
        sections.add(new AXPromptSection("long_term_memory", title(language, "长期用户记忆", "Long-term User Memory"), listText(context.memory().longTermUserMemory(), budget.maxMemoryItems()), 86));
        sections.add(new AXPromptSection("world_summary", title(language, "当前世界会话摘要", "Current World Conversation Summary"), summaryText(context, budget), 84));
        sections.add(new AXPromptSection("short_term_memory", title(language, "短期记忆", "Short-term Memory"), shortTermBlockText(context.memory().shortTermMemoryBlocks(), budget.maxMemoryItems()), 82));
        sections.add(new AXPromptSection("provided_context", title(language, "请求携带上下文", "Provided Context"), context.providedContext(), 80));
        return new AXPromptPlan(profile, sections);
    }

    private String scopeText(AXContextSnapshot context, AXPromptLanguage language) {
        if (language == AXPromptLanguage.EN_US) {
            return "World ID: " + context.scope().worldId() + "; display name: " + context.scope().displayName() + "; writable: " + context.scope().writable();
        }
        return "当前世界标识：" + context.scope().worldId() + "；显示名：" + context.scope().displayName() + "；可写入：" + context.scope().writable();
    }

    private String listText(List<String> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return "";
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).limit(limit).map(value -> "- " + value.trim()).collect(Collectors.joining("\n"));
    }

    private String summaryText(AXContextSnapshot context, AXContextBudget budget) {
        if (context == null || context.memory() == null || context.memory().conversationSummary().isEmpty()) {
            return "";
        }
        if (!context.memory().shortTermMemoryBlocks().isEmpty()) {
            return listText(context.memory().conversationSummary(), 1);
        }
        return listText(context.memory().conversationSummary(), budget.maxMemoryItems());
    }

    private String shortTermBlockText(List<ShortTermMemoryBlock> values, int limit) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isEmpty())
                .skip(Math.max(0, values.size() - limit))
                .map(value -> "- " + value.content())
                .collect(Collectors.joining("\n"));
    }

    private String title(AXPromptLanguage language, String zhCn, String enUs) {
        return language == AXPromptLanguage.EN_US ? enUs : zhCn;
    }
}
