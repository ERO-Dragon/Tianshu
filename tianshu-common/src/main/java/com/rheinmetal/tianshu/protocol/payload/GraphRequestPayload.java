package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record GraphRequestPayload(String itemId, String displayName, int count, String source) implements ITianshuPayload {
    public GraphRequestPayload {
        itemId = itemId == null ? "" : itemId;
        displayName = displayName == null ? "" : displayName;
        count = Math.max(1, count);
        source = source == null || source.isBlank() ? "unknown" : source;
    }
}
