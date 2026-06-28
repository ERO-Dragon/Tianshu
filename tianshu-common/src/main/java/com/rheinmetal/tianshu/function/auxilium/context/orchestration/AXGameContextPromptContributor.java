package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;
import com.rheinmetal.tianshu.function.auxilium.knowledge.AXStaticKnowledgePlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptTexts;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class AXGameContextPromptContributor implements AXPromptContributor {
    private final AXStaticKnowledgePlanner staticKnowledgePlanner;

    public AXGameContextPromptContributor(AXStaticKnowledgePlanner staticKnowledgePlanner) {
        this.staticKnowledgePlanner = staticKnowledgePlanner == null ? AXStaticKnowledgePlanner.NONE : staticKnowledgePlanner;
    }

    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context == null || builder == null) {
            return;
        }
        List<String> lines = new java.util.ArrayList<>();
        List<String> dynamicFacts = runtimeFacts(context);
        if (!dynamicFacts.isEmpty()) {
            lines.add(renderGroup(context, AXPromptTexts.GAME_CONTEXT_DYNAMIC_TITLE, dynamicFacts));
        }
        List<String> knowledgeFacts = staticKnowledge(context);
        if (!knowledgeFacts.isEmpty()) {
            lines.add(renderGroup(context, AXPromptTexts.GAME_CONTEXT_KNOWLEDGE_TITLE, knowledgeFacts));
        }
        String content = lines.stream()
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));
        builder.addSystemMessage(AXPromptSectionRenderer.renderContent(context, AXPromptTexts.SECTION_GAME_CONTEXT, content));
    }

    private List<String> runtimeFacts(AXPromptBuildContext context) {
        if (context.context() == null || context.context().runtimeContextFacts().isEmpty() || context.budget().maxRuntimeContextItems() <= 0) {
            return List.of();
        }
        return context.context().runtimeContextFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(System.currentTimeMillis()))
                .sorted(Comparator.comparingInt(AXRuntimeContextFact::priority).reversed()
                        .thenComparing(Comparator.comparingLong(AXRuntimeContextFact::updatedAtMillis).reversed()))
                .limit(context.budget().maxRuntimeContextItems())
                .map(AXRuntimeContextFact::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .toList();
    }

    private List<String> staticKnowledge(AXPromptBuildContext context) {
        List<LLMPromptRequestPayload.ChunkPayload> chunks = staticKnowledgePlanner.plan(context.request(), context.context(), context.budget());
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .filter(chunk -> chunk != null && chunk.ragContent() != null && !chunk.ragContent().isEmpty())
                .flatMap(chunk -> chunk.ragContent().stream())
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
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
