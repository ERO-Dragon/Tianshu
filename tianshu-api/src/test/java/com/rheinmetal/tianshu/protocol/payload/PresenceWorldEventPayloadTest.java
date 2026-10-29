package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PresenceWorldEventPayloadTest {
    @Test
    void theSingleFactConstructorKeepsTheOldReading() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null, PresenceWorldEventPayload.EVENT_ITEM_CRAFTED, "player", "minecraft:overworld", 1_000L,
                Map.of(PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks"));

        assertEquals(1, payload.occurrences().size());
        assertEquals(1, payload.occurrences().get(0).count());
        assertEquals("minecraft:oak_planks",
                payload.values().get(PresenceWorldEventPayload.VALUE_ITEM_ID));
        assertEquals(1, payload.totalCount());
    }

    @Test
    void aWindowedPayloadTotalsEveryGroup() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null,
                PresenceWorldEventPayload.EVENT_ITEM_PICKUP,
                "player",
                "minecraft:overworld",
                1_000L,
                List.of(
                        new PresenceWorldEventPayload.Occurrence(20, Map.of(
                                PresenceWorldEventPayload.VALUE_ITEM_NAME, "泥土")),
                        new PresenceWorldEventPayload.Occurrence(60, Map.of(
                                PresenceWorldEventPayload.VALUE_ITEM_NAME, "石头"))
                )
        );

        assertEquals(2, payload.occurrences().size());
        assertEquals(80, payload.totalCount());
        assertEquals("泥土", payload.values().get(PresenceWorldEventPayload.VALUE_ITEM_NAME));
    }

    @Test
    void aGroupWithoutValuesIsStillOneOccurrence() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null,
                PresenceWorldEventPayload.EVENT_CONTAINER_OPEN,
                "",
                "",
                1_000L,
                List.of(new PresenceWorldEventPayload.Occurrence(3, null))
        );

        assertEquals(1, payload.occurrences().size());
        assertEquals(3, payload.totalCount());
        assertEquals(Map.of(), payload.values());
    }

    @Test
    void aGroupCannotPretendToHaveHappenedZeroTimes() {
        assertEquals(1, new PresenceWorldEventPayload.Occurrence(0, Map.of()).count());
        assertEquals(1, new PresenceWorldEventPayload.Occurrence(-7, Map.of()).count());
    }

    @Test
    void blankKeysAndValuesAreDroppedLikeTheSingleFactPayloadAlwaysDid() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null, "test:fact", "", "", 1_000L,
                Map.of("keep", "value", "blank", "  ")
        );

        assertEquals(Map.of("keep", "value"), payload.values());
    }

    @Test
    void aPayloadAlwaysCarriesAtLeastOneOccurrence() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null, "test:fact", "", "", 1_000L, List.of());

        assertEquals(1, payload.occurrences().size());
        assertEquals(1, payload.totalCount());
        assertEquals(Map.of(), payload.values());
    }

    @Test
    void aNullValueMapIsNotAnError() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null, "test:fact", "", "", 1_000L, (Map<String, String>) null);

        assertEquals(1, payload.occurrences().size());
        assertEquals(Map.of(), payload.values());
    }

    @Test
    void theOccurrenceListIsImmutable() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(
                null, "test:fact", "player", "", 1_000L, Map.of("key", "value"));

        assertThrows(UnsupportedOperationException.class,
                () -> payload.occurrences().add(new PresenceWorldEventPayload.Occurrence(1, Map.of())));
    }
}
