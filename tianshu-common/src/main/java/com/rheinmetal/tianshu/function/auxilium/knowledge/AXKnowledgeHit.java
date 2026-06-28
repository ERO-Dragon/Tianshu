package com.rheinmetal.tianshu.function.auxilium.knowledge;

import java.util.List;

public record AXKnowledgeHit(
        String uid,
        List<String> facts
) {
    public AXKnowledgeHit {
        uid = uid == null ? "" : uid.trim();
        facts = facts == null ? List.of() : facts.stream()
                .filter(fact -> fact != null && !fact.isBlank())
                .map(String::trim)
                .toList();
    }

    public static AXKnowledgeHit of(String uid, List<String> facts) {
        return new AXKnowledgeHit(uid, facts);
    }
}
