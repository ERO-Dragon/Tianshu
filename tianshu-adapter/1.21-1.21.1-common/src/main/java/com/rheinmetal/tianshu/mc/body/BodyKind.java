package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.BodyCapability;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The carriers this adapter can give an agent. None is a player: one has no body at all beyond a point in the
 * world, one is a floating stand that can hold items, one is a pathfinder mob.
 *
 * <p>Capabilities describe what the carrier is, not which tools exist yet. {@code MOVE} and {@code LOOK} are true of
 * all three; {@code MINE}, {@code ATTACK}, {@code USE}, {@code PLACE} and {@code INVENTORY} belong to the carriers
 * with a living body and a 36-slot store, because those actions go through a player-typed API or an entity path.</p>
 */
public enum BodyKind {

    /** A vanilla marker: invisible, no health, no collision, no inventory and no hands. */
    MARKER(Set.of(BodyCapability.MOVE, BodyCapability.LOOK)),

    /** A gravity-free armour stand: it carries a 36-slot inventory and can use, place, strike and break. */
    STAND(Set.of(BodyCapability.MOVE, BodyCapability.LOOK, BodyCapability.USE, BodyCapability.PLACE,
            BodyCapability.MINE, BodyCapability.ATTACK, BodyCapability.INVENTORY)),

    /** A pathfinder mob: same inventory and actions as the stand, but it can actually walk once navigation is wired. */
    WALKER(Set.of(BodyCapability.MOVE, BodyCapability.LOOK, BodyCapability.USE, BodyCapability.PLACE,
            BodyCapability.MINE, BodyCapability.ATTACK, BodyCapability.INVENTORY));

    private final Set<BodyCapability> capabilities;

    BodyKind(Set<BodyCapability> capabilities) {
        this.capabilities = capabilities;
    }

    public Set<BodyCapability> capabilities() {
        return capabilities;
    }

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the command token; unknown tokens are empty rather than a silent default. */
    public static Optional<BodyKind> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        for (BodyKind kind : values()) {
            if (kind.token().equals(token.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}