package com.rheinmetal.tianshu.dubhe.shared.scope;

import com.rheinmetal.tianshu.api.game.WorldFacts;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DubheWorldIdentityTest {

    private static DubheWorldIdentity identityOf(AtomicReference<WorldFacts> facts) {
        return new DubheWorldIdentity(facts::get);
    }

    @Test
    void platformKindIdsBecomeScopeKinds() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts(WorldFacts.KIND_SERVER_WORLD, "server|example.org", "Example", "minecraft:overworld", true));

        DubheScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(DubheScopeKind.SERVER_WORLD, snapshot.kind());
        assertEquals("server|example.org", snapshot.stableIdentity());
        assertEquals("Example", snapshot.displayName());
        assertTrue(snapshot.writable());
    }

    @Test
    void localWorldKeepsItsOwnKind() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts(WorldFacts.KIND_LOCAL_WORLD, "local|world|Home", "Home", "minecraft:overworld", true));

        DubheScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(DubheScopeKind.LOCAL_WORLD, snapshot.kind());
        assertTrue(snapshot.writable());
    }

    @Test
    void unknownFactsStayUnknownAndNotWritable() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(WorldFacts.UNKNOWN);

        DubheScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(DubheScopeKind.UNKNOWN, snapshot.kind());
        assertFalse(snapshot.writable());
        assertEquals("", snapshot.stableIdentity());
    }

    @Test
    void theSameProviderResumesWhenTheSaveIsIdentified() {
        var facts = new AtomicReference<>(WorldFacts.UNKNOWN);
        var identity = identityOf(facts);
        assertFalse(identity.currentWorldIdentity().writable());
        facts.set(new WorldFacts(WorldFacts.KIND_LOCAL_WORLD, "local|saves|Home-2", "Home", "minecraft:overworld", true));
        var recovered = identity.currentWorldIdentity();
        assertTrue(recovered.writable());
        assertEquals("local|saves|Home-2", recovered.stableIdentity());
    }

    @Test
    void aKindTheModuleDoesNotKnowIsTreatedAsUnknown() {
        AtomicReference<WorldFacts> facts = new AtomicReference<>(
                new WorldFacts("some_future_kind", "x|1", "X", "minecraft:overworld", true));

        DubheScopeSnapshot snapshot = identityOf(facts).currentWorldIdentity();

        assertEquals(DubheScopeKind.UNKNOWN, snapshot.kind());
        assertFalse(snapshot.writable());
    }
}
