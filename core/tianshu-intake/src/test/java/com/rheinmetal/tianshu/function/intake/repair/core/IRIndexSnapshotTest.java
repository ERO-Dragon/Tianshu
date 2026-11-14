package com.rheinmetal.tianshu.function.intake.repair.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IRIndexSnapshotTest {

    @Test
    void builtIndexRoundTripsThroughSnapshot() throws Exception {
        IRCommandService service = serviceWithStone();
        IRSnapshot snapshot = service.snapshot("fp");
        assertNotNull(snapshot);

        byte[] payload = write(snapshot);
        IRSnapshot loaded = IRSnapshot.readFrom(
                new DataInputStream(new ByteArrayInputStream(payload)), payload.length);
        assertEquals("fp", loaded.fingerprint);

        IRCommandService restored = new IRCommandService();
        assertTrue(restored.restore(loaded));
        assertTrue(restored.isReady());
        assertEquals("石头", restored.resolveDisplayName("item:minecraft:stone"));
    }

    @Test
    void snapshotWithPostingOutsideTheDictionaryIsRejected() {
        IRCommandService service = serviceWithStone();
        IRSnapshot snapshot = service.snapshot("fp");
        snapshot.indexPool[0] = 99;

        IRCommandService restored = new IRCommandService();
        assertFalse(restored.restore(snapshot));
        assertFalse(restored.isReady());
    }

    @Test
    void snapshotWithUnboundedArrayLengthIsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x54495231);
            output.writeInt(1);
            output.writeUTF("fp");
            output.writeInt(Integer.MAX_VALUE);
        }
        byte[] payload = bytes.toByteArray();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));

        assertThrows(IOException.class, () -> IRSnapshot.readFrom(input, payload.length));
    }

    private static IRCommandService serviceWithStone() {
        IRCommandService service = new IRCommandService();
        service.rebuild(Map.of("item:minecraft:stone", List.of("石头", "stone")));
        return service;
    }

    private static byte[] write(IRSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            snapshot.writeTo(output);
        }
        return bytes.toByteArray();
    }
}