package com.rheinmetal.tianshu.dubhe.dialogue.gamecontext;

import java.util.List;

public record DubheKnowledgeHit(
        String uid,
        List<String> facts,
        QueryPath queryPath
) {
    public DubheKnowledgeHit {
        uid = uid == null ? "" : uid.trim();
        facts = facts == null ? List.of() : facts.stream()
                .filter(fact -> fact != null && !fact.isBlank())
                .map(String::trim)
                .toList();
        queryPath = queryPath == null ? QueryPath.INPUT_RAG : queryPath;
    }

    public static DubheKnowledgeHit of(String uid, List<String> facts) {
        return new DubheKnowledgeHit(uid, facts, QueryPath.INPUT_RAG);
    }

    public static DubheKnowledgeHit dynamic(String uid, List<String> facts) {
        return new DubheKnowledgeHit(uid, facts, QueryPath.DYNAMIC_RAG);
    }

    public static DubheKnowledgeHit dynamicFacts(String uid, List<String> facts) {
        return new DubheKnowledgeHit(uid, facts, QueryPath.DYNAMIC_FACT);
    }

    public enum QueryPath {
        INPUT_RAG,
        DYNAMIC_FACT,
        DYNAMIC_RAG
    }
}
