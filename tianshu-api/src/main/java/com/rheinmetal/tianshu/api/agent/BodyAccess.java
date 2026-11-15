package com.rheinmetal.tianshu.api.agent;

/**
 * Who may command a body: its owner, the owner plus trusted players, or anyone who can reach the tool.
 *
 * <p>The caller runtime (the assistant, or the server itself) is trusted by construction; this policy governs
 * other players' direct calls. An operator can still change the policy through the body command.</p>
 */
public enum BodyAccess {

    /** Only the owner may command it. */
    OWNER,

    /** The owner and the players the owner trusted. */
    TRUSTED,

    /** Any player who can reach the tool. */
    OPEN
}