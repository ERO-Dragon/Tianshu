package com.rheinmetal.tianshu.function.llm.inference;

import java.util.List;

public record LlmRagContext(List<LlmRagEntry> dynamicRag, LlmRagRoutingContext routing) {
    public static final LlmRagContext EMPTY = new LlmRagContext(List.of(), LlmRagRoutingContext.EMPTY);

    public LlmRagContext {
        if (dynamicRag == null || dynamicRag.isEmpty()) {
            dynamicRag = List.of();
        } else {
            dynamicRag = dynamicRag.stream()
                    .filter(entry -> entry != null && !entry.isEmpty())
                    .map(entry -> new LlmRagEntry(entry.text()))
                    .toList();
        }
        routing = routing == null ? LlmRagRoutingContext.EMPTY : routing;
    }

    public LlmRagContext(List<LlmRagEntry> dynamicRag) {
        this(dynamicRag, LlmRagRoutingContext.EMPTY);
    }

    public static LlmRagContext dynamic(List<String> entries) {
        return dynamic(entries, LlmRagRoutingContext.EMPTY);
    }

    public static LlmRagContext dynamic(List<String> entries, LlmRagRoutingContext routing) {
        if (entries == null || entries.isEmpty()) {
            return new LlmRagContext(List.of(), routing);
        }
        return new LlmRagContext(entries.stream().map(LlmRagEntry::new).toList(), routing);
    }

    public static LlmRagContext routing(LlmRagRoutingContext routing) {
        return new LlmRagContext(List.of(), routing);
    }

    public boolean hasDynamicRag() {
        return !dynamicRag.isEmpty();
    }

    public boolean hasRouting() {
        return routing != null && !routing.isEmpty();
    }
}
