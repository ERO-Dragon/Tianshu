package com.rheinmetal.tianshu.function.ir.enhance;

import java.util.List;

public record IrContextHint(List<String> itemIds) {
    public IrContextHint {
        itemIds = itemIds == null ? List.of() : List.copyOf(itemIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
    }

    public static IrContextHint empty() {
        return new IrContextHint(List.of());
    }
}
