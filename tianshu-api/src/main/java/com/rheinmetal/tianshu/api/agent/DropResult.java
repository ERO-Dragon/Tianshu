package com.rheinmetal.tianshu.api.agent;

/** What a drop actually threw on the ground; an empty id with zero count means nothing was dropped. */
public record DropResult(String itemId, int dropped) {

    public DropResult {
        if (itemId == null) {
            throw new IllegalArgumentException("itemId is required");
        }
        if (dropped < 0) {
            throw new IllegalArgumentException("dropped must not be negative");
        }
    }
}