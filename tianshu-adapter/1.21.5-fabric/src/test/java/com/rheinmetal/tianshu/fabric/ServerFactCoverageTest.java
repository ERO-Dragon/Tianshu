package com.rheinmetal.tianshu.fabric;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which server facts this loader produces, as a table the build enforces.
 *
 * <p>Fabric's native server events do not cover every fact the NeoForge component produces. The difference is
 * product-facing — a consumer sees fewer events on this loader — so it is written down here as data: adding a
 * kind means adding it to the supported set, and gaining one for free means deleting it from the absent set.
 * A silent change in either direction fails.</p>
 */
final class ServerFactCoverageTest {
    private static final Path COLLECTOR =
            Path.of("src/main/java/com/rheinmetal/tianshu/fabric/FabricGameFactCollector.java");

    /** Produced through a native Fabric server event. */
    private static final Set<String> PRODUCED = Set.of(
            "EVENT_DAMAGE_TAKEN",
            "EVENT_PLAYER_DEATH",
            "EVENT_MOB_KILLED",
            "EVENT_BLOCK_BROKEN",
            "EVENT_FRIEND_JOIN",
            "EVENT_FRIEND_LEAVE");

    /** Would need a mixin or a poll; absent here, and their consumers simply receive nothing on this loader. */
    private static final Set<String> ABSENT = Set.of(
            "EVENT_ITEM_CRAFTED",
            "EVENT_FISHING_CATCH",
            "EVENT_TAMED",
            "EVENT_BLOCK_PLACED",
            "EVENT_MOUNT",
            "EVENT_DISMOUNT",
            "EVENT_EFFECT_GAINED",
            "EVENT_EFFECT_EXPIRED",
            "EVENT_EXP_LEVEL",
            "EVENT_ITEM_PICKUP",
            "EVENT_TRADE");

    @Test
    void producedAndAbsentFactsAreExactlyWhatThisLoaderClaims() throws Exception {
        String source = Files.readString(COLLECTOR, StandardCharsets.UTF_8);

        TreeSet<String> missing = new TreeSet<>();
        for (String kind : PRODUCED) {
            if (!source.contains("WorldEventTypes." + kind)) {
                missing.add(kind);
            }
        }
        assertTrue(missing.isEmpty(), "declared as produced but never published: " + missing);

        TreeSet<String> unexpected = new TreeSet<>();
        for (String kind : ABSENT) {
            if (source.contains("WorldEventTypes." + kind)) {
                unexpected.add(kind);
            }
        }
        assertFalse(!unexpected.isEmpty(),
                "declared as absent but now published; move it to PRODUCED: " + unexpected);
    }
}