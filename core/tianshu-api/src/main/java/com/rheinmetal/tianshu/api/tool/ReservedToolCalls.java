package com.rheinmetal.tianshu.api.tool;

/** Ids the dispatcher itself understands. They are not registered tools and never enter the catalogue. */
public final class ReservedToolCalls {

    public static final ToolId CANCEL = ToolId.of("tianshu", "tool.cancel");
    public static final ToolId CATALOG = ToolId.of("tianshu", "tool.catalog");

    public static final String CANCEL_ID = CANCEL.toString();
    public static final String CATALOG_ID = CATALOG.toString();

    private ReservedToolCalls() {
    }
}
