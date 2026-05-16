package com.rheinmetal.tianshu.function.assistant.prompt;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextBudget;
import com.rheinmetal.tianshu.function.assistant.context.AssistantContextSnapshot;
import com.rheinmetal.tianshu.function.assistant.memory.ShortTermMemoryBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AssistantPromptPlanner {
    private final AssistantPromptResourceRepository resourceRepository;

    public AssistantPromptPlanner() {
        this(null);
    }

    public AssistantPromptPlanner(AssistantPromptResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    public AssistantPromptPlan plan(AssistantRequest request, AssistantContextSnapshot context, AssistantContextBudget budget) {
        AssistantPromptLanguage language = AssistantPromptLanguage.fromText(request == null ? "" : request.userText());
        return plan(request, context, budget, AssistantPromptRequest.general(language));
    }

    public AssistantPromptPlan plan(AssistantRequest request, AssistantContextSnapshot context, AssistantContextBudget budget, AssistantPromptRequest promptRequest) {
        AssistantPromptLanguage language = promptRequest == null ? AssistantPromptLanguage.fromText(request == null ? "" : request.userText()) : promptRequest.language();
        AssistantPromptTask task = promptRequest == null ? AssistantPromptTask.GENERAL_ASSISTANT : promptRequest.task();
        String variant = promptRequest == null ? "default" : promptRequest.variant();
        AssistantPromptProfile profile = resourceRepository == null
                ? AssistantPromptProfile.defaultFor(task, language)
                : resourceRepository.loadProfile(task, language, variant);
        List<AssistantPromptSection> sections = new ArrayList<>();
        sections.add(new AssistantPromptSection("identity", title(language, "身份", "Identity"), profile.identity(), 100));
        sections.add(new AssistantPromptSection("rules", title(language, "行为规则", "Behavior Rules"), profile.behaviorRules(), 98));
        if (context == null) {
            return new AssistantPromptPlan(profile, sections);
        }
        sections.add(new AssistantPromptSection("persona", title(language, "助手人设", "Persona"), context.memory().persona(), 95));
        sections.add(new AssistantPromptSection("scope", title(language, "作用域", "Scope"), scopeText(context, language), 92));
        sections.add(new AssistantPromptSection("long_term_memory", title(language, "长期用户记忆", "Long-term User Memory"), listText(context.memory().longTermUserMemory(), budget.maxMemoryItems()), 86));
        sections.add(new AssistantPromptSection("world_summary", title(language, "当前世界会话摘要", "Current World Conversation Summary"), summaryText(context, budget), 84));
        sections.add(new AssistantPromptSection("short_term_memory", title(language, "短期记忆", "Short-term Memory"), shortTermBlockText(context.memory().shortTermMemoryBlocks(), budget.maxMemoryItems()), 82));
        sections.add(new AssistantPromptSection("provided_context", title(language, "请求携带上下文", "Provided Context"), context.providedContext(), 80));
        return new AssistantPromptPlan(profile, sections);
    }

    private String scopeText(AssistantContextSnapshot context, AssistantPromptLanguage language) {
        if (language == AssistantPromptLanguage.EN_US) {
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

    private String summaryText(AssistantContextSnapshot context, AssistantContextBudget budget) {
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

    private String title(AssistantPromptLanguage language, String zhCn, String enUs) {
        return language == AssistantPromptLanguage.EN_US ? enUs : zhCn;
    }
}
