package com.rheinmetal.tianshu.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.auxilium.AXRequest;
import com.rheinmetal.tianshu.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.auxilium.core.context.AXContextSnapshot;

import java.util.List;

public interface AXGameContextKnowledgePlanner {
    AXGameContextKnowledgePlanner NONE = (request, context, budget) -> List.of();

    List<AXKnowledgeHit> plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget);
}
