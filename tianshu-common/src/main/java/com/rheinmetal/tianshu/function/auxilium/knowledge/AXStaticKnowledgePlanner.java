package com.rheinmetal.tianshu.function.auxilium.knowledge;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;

import java.util.List;

public interface AXStaticKnowledgePlanner {
    AXStaticKnowledgePlanner NONE = (request, context, budget) -> List.of();

    List<AXKnowledgeHit> plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget);
}
