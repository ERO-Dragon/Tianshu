package com.rheinmetal.tianshu.dubhe.module.gamecontext;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.core.context.DubheContextSnapshot;

import java.util.List;

public interface DubheGameContextKnowledgePlanner {
    DubheGameContextKnowledgePlanner NONE = (request, context, budget) -> List.of();

    List<DubheKnowledgeHit> plan(DubheRequest request, DubheContextSnapshot context, DubheContextBudget budget);
}
