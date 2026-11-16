package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolCaller;

import java.util.Objects;

/** Who is acting: a player, or a server-side agent body. */
public record ActorRef(String id, Kind kind, boolean operator) {

    public enum Kind {
        PLAYER,
        BODY
    }

    public ActorRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
    }

    public static ActorRef player(String playerId, boolean operator) {
        return new ActorRef(playerId, Kind.PLAYER, operator);
    }

    public static ActorRef body(String bodyId, boolean operator) {
        return new ActorRef(bodyId, Kind.BODY, operator);
    }

    public static ActorRef of(ToolCaller caller) {
        Objects.requireNonNull(caller, "caller");
        return switch (caller.kind()) {
            case PLAYER -> player(caller.id(), caller.operator());
            case BODY -> body(caller.id(), caller.operator());
            default -> throw new IllegalArgumentException(
                    "caller " + caller.kind() + " is not an actor that can act in the world");
        };
    }
}
