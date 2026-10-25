package com.rheinmetal.tianshu.function.auxilium.scope;

import com.rheinmetal.tianshu.api.game.WorldFacts;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuxiliumWorldIdentityTest {

    private static AuxiliumWorldIdentity identityOf(AtomicReference<WorldFacts> facts) {
        return new AuxiliumWorldIdentity(facts::get);
    }

    @Test
    void platformKindIdsBecomeScopeKinds() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts(WorldFacts.KIND_SERVER_WORLD, "server|example.org", "Example", "minecraft:overworld", true));

        AXScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(AXScopeKind.SERVER_WORLD, snapshot.kind());
        assertEquals("server|example.org", snapshot.stableIdentity());
        assertEquals("Example", snapshot.displayName());
        assertTrue(snapshot.writable());
    }

    @Test
    void localWorldKeepsItsOwnKind() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts(WorldFacts.KIND_LOCAL_WORLD, "local|world|Home", "Home", "minecraft:overworld", true));

        AXScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(AXScopeKind.LOCAL_WORLD, snapshot.kind());
        assertTrue(snapshot.writable());
    }

    @Test
    void unknownFactsStayUnknownAndNotWritable() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(WorldFacts.UNKNOWN);

        AXScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(AXScopeKind.UNKNOWN, snapshot.kind());
        assertFalse(snapshot.writable());
        assertEquals("", snapshot.stableIdentity());
    }

    @Test
    void aKindTheModuleDoesNotKnowIsTreatedAsUnknown() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts("some_future_kind", "x|1", "X", "minecraft:overworld", true));

        AXScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(AXScopeKind.UNKNOWN, snapshot.kind());
        assertFalse(snapshot.writable());
    }
}
