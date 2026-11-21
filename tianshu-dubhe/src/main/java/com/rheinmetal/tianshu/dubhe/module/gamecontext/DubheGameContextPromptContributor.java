package com.rheinmetal.tianshu.dubhe.module.gamecontext;

import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptAssemblyBuilder;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptBuildContext;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptContributor;
import com.rheinmetal.tianshu.dubhe.core.prompt.DubhePromptSectionRenderer;
import com.rheinmetal.tianshu.dubhe.module.system.DubhePromptTexts;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class DubheGameContextPromptContributor implements DubhePromptContributor {
    public static final String SECTION_ID = "game_context";

    private final DubheGameContextKnowledgePlanner knowledgePlanner;

    public DubheGameContextPromptContributor(DubheGameContextKnowledgePlanner knowledgePlanner) {
        this.knowledgePlanner = knowledgePlanner == null ? DubheGameContextKnowledgePlanner.NONE : knowledgePlanner;
    }

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(DubhePromptBuildContext context, DubhePromptAssemblyBuilder builder) {
        if (context == null || builder == null) {
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        List<DubheKnowledgeHit> knowledgeHits = knowledgePlanner.plan(context.request(), context.context(), context.budget());
        int knowledgeLimit = Math.max(0, context.budget().maxKnowledgeRagItems());
        List<String> dynamicContent = dynamicContent(context, knowledgeHits, knowledgeLimit);
        if (!dynamicContent.isEmpty()) {
            lines.add(renderGroup(context, DubhePromptTexts.GAME_CONTEXT_CURRENT_SITUATION_INTRO, dynamicContent));
        }
        List<String> staticContent = staticContent(context, knowledgeHits, Math.max(0, knowledgeLimit - dynamicContent.size()));
        if (!staticContent.isEmpty()) {
            lines.add(renderGroup(context, DubhePromptTexts.GAME_CONTEXT_RELEVANT_KNOWLEDGE_INTRO, staticContent));
        }
        String content = lines.stream()
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (content.isBlank()) {
            return;
        }
        builder.addTailSection(DubhePromptSectionRenderer.renderContent(context, DubhePromptTexts.SECTION_GAME_CONTEXT, content));
    }

    private List<String> dynamicFacts(DubhePromptBuildContext context, int limit) {
        if (context.context() == null || context.context().dynamicFacts().isEmpty() || limit <= 0) {
            return List.of();
        }
        return context.context().dynamicFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(System.currentTimeMillis()))
                .sorted(Comparator.comparingInt(DubheDynamicFact::priority).reversed()
                        .thenComparing(Comparator.comparingLong(DubheDynamicFact::updatedAtMillis).reversed()))
                .limit(limit)
                .map(DubheDynamicFact::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .toList();
    }

    private List<String> dynamicContent(DubhePromptBuildContext context, List<DubheKnowledgeHit> hits, int limit) {
        List<String> selectedDynamicFacts = knowledgeFacts(hits, DubheKnowledgeHit.QueryPath.DYNAMIC_FACT, limit);
        List<String> lines = new java.util.ArrayList<>(selectedDynamicFacts.isEmpty()
                ? dynamicFacts(context, limit)
                : selectedDynamicFacts);
        int remaining = Math.max(0, limit - lines.size());
        lines.addAll(knowledgeFacts(hits, DubheKnowledgeHit.QueryPath.DYNAMIC_RAG, remaining));
        return lines.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> staticContent(DubhePromptBuildContext context, List<DubheKnowledgeHit> hits, int limit) {
        return knowledgeFacts(hits, DubheKnowledgeHit.QueryPath.INPUT_RAG, limit);
    }

    private List<String> knowledgeFacts(List<DubheKnowledgeHit> hits, DubheKnowledgeHit.QueryPath queryPath, int limit) {
        if (hits == null || hits.isEmpty() || limit <= 0) {
            return List.of();
        }
        return hits.stream()
                .filter(hit -> hit != null && hit.queryPath() == queryPath && hit.facts() != null && !hit.facts().isEmpty())
                .flatMap(hit -> hit.facts().stream())
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
    }

    private String renderGroup(DubhePromptBuildContext context, String titleKey, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        String title = context.texts().text(titleKey);
        if (!title.isBlank()) {
            content.append(title).append('\n');
        }
        content.append(lines.stream()
                .map(text -> DubhePromptSectionRenderer.renderLine(context, DubhePromptTexts.GAME_CONTEXT_FACT_LINE, "fact", text))
                .collect(Collectors.joining("\n")));
        return content.toString();
    }
}
