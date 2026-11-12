package com.rheinmetal.tianshu.api.tool;

/** Who is allowed to invoke a tool. */
public enum ToolPermission {

    /** Any caller, including another mod's plain logic. */
    EVERYONE,

    /** Only an operator: a player with permission level 2 or higher, or a caller that proves the same right. */
    OPERATOR
}