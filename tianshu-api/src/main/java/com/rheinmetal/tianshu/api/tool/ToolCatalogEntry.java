package com.rheinmetal.tianshu.api.tool;

/**
 * One tool in the server catalogue: which tool, and whether this server currently allows it.
 *
 * <p>The switch has to travel with the entry. A catalogue of bare ids leaves the receiver to apply its own default,
 * and the world-writing tools default to off — so a server that enabled one kept answering {@code DISABLED} on the
 * client, with nothing sent and nothing to explain it.</p>
 */
public record ToolCatalogEntry(String toolId, boolean enabled) {

    public ToolCatalogEntry {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId cannot be blank");
        }
    }

    /** The same tool, with the switch this server reports for it. */
    public static ToolCatalogEntry of(ToolSpec spec) {
        return new ToolCatalogEntry(spec.id().toString(), spec.enabled());
    }
}