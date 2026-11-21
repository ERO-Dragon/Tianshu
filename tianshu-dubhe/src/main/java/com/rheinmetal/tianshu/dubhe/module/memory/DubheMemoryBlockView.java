package com.rheinmetal.tianshu.dubhe.module.memory;

import java.util.List;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;

public record DubheMemoryBlockView(
        DubheStmBlock block,
        List<String> attachedMessages
) {
    public DubheMemoryBlockView {
        attachedMessages = attachedMessages == null ? List.of() : attachedMessages.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean isEmpty() {
        return block == null || block.isEmpty();
    }

    public String content() {
        return block == null ? "" : block.content();
    }

    public int tokenCount() {
        if (block == null) {
            return 0;
        }
        return block.tokenCount();
    }

    public static DubheMemoryBlockView of(DubheStmBlock block) {
        return new DubheMemoryBlockView(block, List.of());
    }
}
