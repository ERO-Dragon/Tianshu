package com.rheinmetal.tianshu.mc.tool;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerTransferSourceBoundaryTest {

    @Test
    void containerToActorTransferUsesTheRequestedSlotAndHonorsItsCapacity() throws Exception {
        String source = Files.readString(Path.of(
                "../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/tool/ContainerTransferSource.java"),
                StandardCharsets.UTF_8);
        String transfer = methodBody(source, "private static ContainerTransferResult containerToActor");
        String targetSlot = methodBody(source, "static int addToActorSlot");

        assertTrue(transfer.contains("addToActorSlot(inventory, moving, request.playerSlot())"));
        assertFalse(transfer.contains("Inventories.add"));
        assertTrue(targetSlot.contains("isSameItemSameComponents"));
        assertTrue(targetSlot.contains("inventory.getMaxStackSize()"));
        assertTrue(targetSlot.contains("inventory.setItem(slot, placed)"));
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
