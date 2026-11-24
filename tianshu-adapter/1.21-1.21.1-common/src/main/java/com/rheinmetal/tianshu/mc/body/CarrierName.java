package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.IdSegments;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.entity.EntityTypes;

import java.util.Optional;

/**
 * The names a spawn request may use, and who may use a namespaced one.
 *
 * <p>Three rules, each answered without touching the game: a token is either a built-in shape ({@code marker},
 * {@code stand}, {@code walker}) or a well-formed {@code namespace:name} id; a malformed name names nothing; and a
 * declared type may only be spawned by the module its namespace names. The adapter's registry supplies the one thing
 * this class cannot know — whether the id exists in this process — and
 * {@link BodyCarriers#resolve(String, String)} combines the two answers in that order.</p>
 */
final class CarrierName {

    private CarrierName() {
    }

    /** The built-in shape a token names, or empty when the token is meant to be a declared type. */
    static Optional<BodyKind> builtin(String token) {
        return BodyKind.parse(token);
    }

    /**
     * Refuses a name that is not a namespaced id in this id grammar.
     *
     * <p>Shape only: whether such a type exists is the registry's question, and it is asked afterwards so that a typo
     * in a module's own namespace is reported as "no such type" rather than as somebody else's. A null name is a
     * programming error, not a request, so it stays a {@link NullPointerException}.</p>
     */
    static void requireWellFormed(String id) {
        try {
            String[] halves = IdSegments.split(id, "body type id");
            IdSegments.require(halves[0], "body type id", "namespace");
            IdSegments.require(halves[1], "body type id", "name");
        } catch (IllegalArgumentException malformed) {
            // The id grammar reports a programming error; a spawn request has to answer in the tool vocabulary.
            throw BodyFailure.KIND_INVALID.exception();
        }
    }

    /** Refuses a declared type that belongs to another module. */
    static void requireOwned(String moduleId, String id) {
        if (!EntityTypes.ownedBy(moduleId, id)) {
            throw BodyFailure.KIND_FOREIGN.exception();
        }
    }
}