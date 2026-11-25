package com.rheinmetal.tianshu.mc.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary between the transfer tool and the storage it moves items in: the tool must address slots the same way
 * the inspect tool reports them, and it must not write anything the store refused.
 */
class ContainerTransferSourceBoundaryTest {

    private static final String SOURCES = "../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/tool/";

    @Test
    void transferMovesByTheRequestedSlotThroughTheResolvedStore() throws Exception {
        String transfer = read(SOURCES + "ContainerTransferSource.java");
        String toActor = methodBody(transfer, "private static ContainerTransferResult blockToActor");
        String toBlock = methodBody(transfer, "private static ContainerTransferResult actorToBlock");

        // The requested slot is the slot used, on both sides and in both directions.
        assertTrue(toActor.contains("actor.insert(request.playerSlot(), moving)"));
        assertTrue(toBlock.contains("block.insert(request.containerSlot(), moving)"));
        // And the move is never a bulk inventory add: that ignored the slot the caller asked for.
        assertFalse(toActor.contains("Inventories.add"));
        assertFalse(toBlock.contains("Inventories.add"));
        // The block side is resolved through the shared resolution, which is what makes its slot numbers the ones
        // container.inspect reports — two different resolutions would let a caller fill a slot it never saw.
        assertTrue(transfer.contains("ItemStores.of(holder.level(), pos, probe)"));
    }

    @Test
    void theStoreHonorsSlotCapacityBeforeAnythingIsWritten() throws Exception {
        String container = read(SOURCES + "ContainerStore.java");
        assertTrue(container.contains("isSameItemSameComponents"), "a slot holding another item takes nothing");
        assertTrue(container.contains("container.getMaxStackSize()"), "capacity comes from the container");
        assertTrue(container.contains("container.canPlaceItem(slot, stack)"), "the container's own rule decides");
    }

    @Test
    void theResolutionPrefersTheLoadersOwnStorageOverTheVanillaWrapper() throws Exception {
        String resolver = read(SOURCES + "ItemStores.java");
        int handled = resolver.indexOf("effective.items(level, pos)");
        int fallback = resolver.indexOf("instanceof Container");
        assertTrue(handled >= 0, "the loader's item storage is consulted at all");
        assertTrue(fallback > handled, "the vanilla container is the fallback, not the first choice");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(opening + 1, index);
            }
        }
        throw new AssertionError("Missing method: " + signature);
    }
}