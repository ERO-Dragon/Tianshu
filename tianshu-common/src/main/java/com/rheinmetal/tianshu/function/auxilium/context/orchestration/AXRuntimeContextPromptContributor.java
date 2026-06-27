package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class AXRuntimeContextPromptContributor implements AXPromptContributor {
    @Override
    public void contribute(AXPromptBuildContext context, AXPromptAssemblyBuilder builder) {
        if (context.context() == null || context.context().runtimeContextFacts().isEmpty() || context.budget().maxRuntimeContextItems() <= 0) {
            return;
        }
        List<String> facts = context.context().runtimeContextFacts().stream()
                .filter(fact -> fact != null && !fact.isEmpty() && !fact.isExpired(System.currentTimeMillis()))
                .sorted(Comparator.comparingInt(AXRuntimeContextFact::priority).reversed()
                        .thenComparing(Comparator.comparingLong(AXRuntimeContextFact::updatedAtMillis).reversed()))
                .limit(context.budget().maxRuntimeContextItems())
                .map(AXRuntimeContextFact::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .toList();
        if (facts.isEmpty()) {
            return;
        }
        builder.addSystemMessage(wrap("game_context", facts.stream().map(text -> "- " + text.trim()).collect(Collectors.joining("\n"))));
    }

    private String wrap(String tag, String content) {
        return "<" + tag + ">\n" + content + "\n</" + tag + ">";
    }
}
