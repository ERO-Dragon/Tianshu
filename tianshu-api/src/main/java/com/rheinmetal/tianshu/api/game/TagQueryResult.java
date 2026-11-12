package com.rheinmetal.tianshu.api.game;

import java.util.List;

/** The answer to a {@link TagQuery}: tag ids for an item query, entry ids for a tag query, sorted. */
public record TagQueryResult(List<String> entries) {
}
