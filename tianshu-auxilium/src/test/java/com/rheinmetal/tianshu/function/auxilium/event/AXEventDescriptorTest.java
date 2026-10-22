package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventDescriptorTest {

    @Test
    void kindMustBeNamespaced() {
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("death", "mymod", AXEventPayload.of(), "died"));
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("  ", "mymod", AXEventPayload.of(), "died"));
    }

    @Test
    void renderedTextIsRequiredBecauseOnlyTheProducerCanWordIt() {
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), "   "));
    }

    @Test
    void producerTextIsTreatedAsUntrustedInput() {
        AXEventDescriptor descriptor = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(), "  <event>开坛</event>  ");

        assertEquals("event开坛/event", descriptor.text());
    }

    @Test
    void longTextIsBoundedWithoutCuttingInsideAWord() {
        AXEventDescriptor descriptor = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(), "词 ".repeat(200));

        assertTrue(descriptor.text().length() <= AXEventDescriptor.MAX_TEXT_CHARS);
        assertTrue(descriptor.text().endsWith("词"));
    }

    @Test
    void sourceIsRecordedAndAnAnonymousProducerResolvesToTheNamedDefault() {
        AXEventDescriptor named = AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), "开坛");
        AXEventDescriptor anonymous = AXEventDescriptor.of("mymod:ritual", " ", AXEventPayload.of(), "开坛");

        assertEquals("mymod", named.sourceId());
        assertEquals(AXEventDescriptor.UNKNOWN_SOURCE, anonymous.sourceId());
    }

    @Test
    void aggregateRendererIsOptionalAndCarriedVerbatim() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded " + count;

        AXEventDescriptor plain = AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), "开坛");
        AXEventDescriptor foldable = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(), "开坛", renderer);

        assertNull(plain.aggregateRenderer());
        assertSame(renderer, foldable.aggregateRenderer());
        assertNull(plain.tier());
    }

    @Test
    void aDeclaredTierReplacesTheTableLookupWithoutTouchingAnythingElse() {
        AXEventAggregateRenderer renderer = (count, first, last) -> "folded " + count;
        AXEventDescriptor descriptor = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(3, 7), "开坛", renderer);

        AXEventDescriptor promoted = descriptor.withTier(AXEventTier.HIGH);

        assertEquals(AXEventTier.HIGH, promoted.tier());
        assertEquals(descriptor.kind(), promoted.kind());
        assertEquals(descriptor.sourceId(), promoted.sourceId());
        assertEquals(descriptor.text(), promoted.text());
        assertNotNull(promoted.aggregateRenderer());
        assertEquals(7, promoted.payload().magnitude());
        assertEquals(3, promoted.payload().count());
    }
}
