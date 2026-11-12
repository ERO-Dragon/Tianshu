package com.rheinmetal.tianshu.api.game;

import java.util.List;

/** The plausible drops of one block or entity, de-duplicated and sorted. */
public record LootQueryResult(List<String> items) {
}
