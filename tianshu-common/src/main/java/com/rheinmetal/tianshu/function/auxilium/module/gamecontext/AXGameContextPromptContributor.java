package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssemblyBuilder;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptBuildContext;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptContributor;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptSectionRenderer;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptTexts;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class AXGameContextPromptContributor implements AXPromptContributor {
    public static final String SECTION_ID = "game_context";

    private final AXGameContextKnowledgePlanner knowledgePlanner;

    public AXGameContextPromptContributor(AXGameContextKnowledgePlanner knowledgePlanner) {
        this.knowledgePlanner = knowledgePlanner == null ? AXGameContextKnowledgePlanner.NONE : knowledgePlanner;
    }

    @Override
    public String sectionId() {
        return SECTION_ID;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context == null || builder == null) {
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        List<AXKnowledgeHit> knowledgeHits = knowledgePlanner.plan(context.request(), context.context(), context.budget());
        List<String> dynamicContent = dynamicContent(context, knowledgeHits);
        if (!dynamicContent.isEmpty()) {
            lines.add(renderGroup(context, AXPromptTexts.GAME_CONTEXT_DYNAMIC_CONTENT_TITLE, dynamicContent));
        }
        List<String> staticContent = staticContent(context, knowledgeHits);
        if (!staticContent.isEmpty()) {
            lines.add(renderGroup(context, AXPromptTexts.GAME_CONTEXT_STATIC_CONTENT_TITLE, staticContent));
        }
        String content = lines.stream()
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (content.isBlank()) {
            return;
        }
        builder.addContextSection(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_GAME_CONTEXT, content));
    }

    private List<String> dynamicFacts(AXPromptBuildContext context, int limit) {
        if (context.context() == null || context.context().dynamicFacts().isEmpty() || limit <= 0) {
            return List.of();
        }
        return context.context().dynamicFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(System.currentTimeMillis()))
                .sorted(Comparator.comparingInt(AXDynamicFact::priority).reversed()
                        .thenComparing(Comparator.comparingLong(AXDynamicFact::updatedAtMillis).reversed()))
                .limit(limit)
                .map(AXDynamicFact::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .toList();
    }

    private List<String> dynamicContent(AXPromptBuildContext context, List<AXKnowledgeHit> hits) {
        int limit = context.budget().maxDynamicContentItems();
        List<String> selectedDynamicFacts = knowledgeFacts(hits, AXKnowledgeHit.QueryPath.DYNAMIC_FACT, limit);
        List<String> lines = new java.util.ArrayList<>(selectedDynamicFacts.isEmpty()
                ? dynamicFacts(context, limit)
                : selectedDynamicFacts);
        int remaining = Math.max(0, limit - lines.size());
        lines.addAll(knowledgeFacts(hits, AXKnowledgeHit.QueryPath.DYNAMIC_RAG, remaining));
        return lines.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> staticContent(AXPromptBuildContext context, List<AXKnowledgeHit> hits) {
        return knowledgeFacts(hits, AXKnowledgeHit.QueryPath.INPUT_RAG, context.budget().maxStaticContentItems());
    }

    private List<String> knowledgeFacts(List<AXKnowledgeHit> hits, AXKnowledgeHit.QueryPath queryPath, int limit) {
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

    private String renderGroup(AXPromptBuildContext context, String titleKey, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        String title = context.texts().text(titleKey);
        if (!title.isBlank()) {
            content.append(title).append('\n');
        }
        content.append(lines.stream()
                .map(text -> AXPromptSectionRenderer.renderLine(context, AXPromptTexts.GAME_CONTEXT_FACT_LINE, "fact", text))
                .collect(Collectors.joining("\n")));
        return content.toString();
    }
}
