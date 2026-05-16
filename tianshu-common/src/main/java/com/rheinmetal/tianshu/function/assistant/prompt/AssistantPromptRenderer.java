package com.rheinmetal.tianshu.function.assistant.prompt;

import com.rheinmetal.tianshu.function.assistant.context.AssistantContextBudget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AssistantPromptRenderer {
    public String renderSystemPrompt(AssistantPromptPlan plan, AssistantContextBudget budget) {
        AssistantContextBudget effectiveBudget = budget == null ? AssistantContextBudget.DEFAULT : budget;
        if (plan == null) {
            return "你是天枢 Minecraft 模组中的随行助手。";
        }
        StringBuilder builder = new StringBuilder();
        orderedSections(plan).stream()
                .filter(section -> section != null && !section.isEmpty())
                .forEach(section -> builder.append("## ").append(section.title()).append('\n').append(section.content()).append("\n\n"));
        String result = builder.toString().trim();
        if (result.isBlank()) {
            return plan.profile().identity();
        }
        if (result.length() <= effectiveBudget.maxSystemChars()) {
            return result;
        }
        return result.substring(0, effectiveBudget.maxSystemChars()) + "\n[上下文已按预算截断]";
    }

    private List<AssistantPromptSection> orderedSections(AssistantPromptPlan plan) {
        List<AssistantPromptSection> sections = plan.sections().stream()
                .filter(section -> section != null && !section.isEmpty())
                .toList();
        List<String> order = plan.profile().sectionOrder();
        if (order == null || order.isEmpty()) {
            return sections.stream().sorted(Comparator.comparingInt(AssistantPromptSection::priority).reversed()).toList();
        }
        List<AssistantPromptSection> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (String key : order) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String normalizedKey = key.trim();
            sections.stream()
                    .filter(section -> normalizedKey.equals(section.key()))
                    .findFirst()
                    .ifPresent(section -> {
                        result.add(section);
                        used.add(section.key());
                    });
        }
        sections.stream()
                .filter(section -> !used.contains(section.key()))
                .sorted(Comparator.comparingInt(AssistantPromptSection::priority).reversed())
                .forEach(result::add);
        return List.copyOf(result);
    }
}
