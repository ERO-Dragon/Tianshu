package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import java.util.List;

public record AXKnowledgeHit(
        String uid,
        List<String> facts,
        QueryPath queryPath
) {
    public AXKnowledgeHit {
        uid = uid == null ? "" : uid.trim();
        facts = facts == null ? List.of() : facts.stream()
                .filter(fact -> fact != null && !fact.isBlank())
                .map(String::trim)
                .toList();
        queryPath = queryPath == null ? QueryPath.INPUT_RAG : queryPath;
    }

    public static AXKnowledgeHit of(String uid, List<String> facts) {
        return new AXKnowledgeHit(uid, facts, QueryPath.INPUT_RAG);
    }

    public static AXKnowledgeHit dynamic(String uid, List<String> facts) {
        return new AXKnowledgeHit(uid, facts, QueryPath.DYNAMIC_RAG);
    }

    public enum QueryPath {
        INPUT_RAG,
        DYNAMIC_RAG
    }
}
