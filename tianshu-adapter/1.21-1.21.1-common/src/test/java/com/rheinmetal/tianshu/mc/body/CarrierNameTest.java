package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.entity.BodyToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard in front of spawning, on its own: what a name may be, and who may use which name.
 *
 * <p>Whether a well-formed id actually exists is the adapter registry's question and is asked afterwards; these cases
 * lock the two rules that need no game to decide.</p>
 */
class CarrierNameTest {

    @Test
    void theBuiltInShapesAreTheirOwnTokens() {
        assertEquals(Optional.of(BodyKind.MARKER), CarrierName.builtin("marker"));
        assertEquals(Optional.of(BodyKind.WALKER), CarrierName.builtin("WALKER"));
        assertTrue(CarrierName.builtin("lab:orb").isEmpty(), "a namespaced id is not a shape");
        assertTrue(CarrierName.builtin("nothing").isEmpty());
    }

    @Test
    void aDeclaredNameHasToBeANamespacedId() {
        CarrierName.requireWellFormed("lab:orb");

        assertRefused(BodyFailure.KIND_INVALID, () -> CarrierName.requireWellFormed("orb"));
        assertRefused(BodyFailure.KIND_INVALID, () -> CarrierName.requireWellFormed("lab:"));
        assertRefused(BodyFailure.KIND_INVALID, () -> CarrierName.requireWellFormed(":orb"));
        assertRefused(BodyFailure.KIND_INVALID, () -> CarrierName.requireWellFormed("lab:Not Valid"));
    }

    /** The namespace is the ownership, so a second module cannot ask for a type it did not declare. */
    @Test
    void aDeclaredNameBelongsToTheModuleInItsNamespace() {
        CarrierName.requireOwned("lab", "lab:orb");

        assertRefused(BodyFailure.KIND_FOREIGN, () -> CarrierName.requireOwned("dubhe", "lab:orb"));
        assertRefused(BodyFailure.KIND_FOREIGN, () -> CarrierName.requireOwned("labx", "lab:orb"));
    }

    private static void assertRefused(BodyFailure expected, Executable call) {
        BodyToolException refused = assertThrows(BodyToolException.class, call);
        assertEquals(expected, refused.reason(),
                "the reply has to name the rule that refused");
    }
}