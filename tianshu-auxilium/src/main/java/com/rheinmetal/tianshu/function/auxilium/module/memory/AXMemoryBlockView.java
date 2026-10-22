package com.rheinmetal.tianshu.function.auxilium.module.memory;

import java.util.List;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;

public record AXMemoryBlockView(
        AXStmBlock block,
        List<String> attachedMessages
) {
    public AXMemoryBlockView {
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

    public static AXMemoryBlockView of(AXStmBlock block) {
        return new AXMemoryBlockView(block, List.of());
    }
}
