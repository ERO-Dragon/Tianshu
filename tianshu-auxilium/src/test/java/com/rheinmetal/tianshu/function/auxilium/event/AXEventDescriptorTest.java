package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXEventDescriptorTest {
    private static final AXEventText LINE = AXEventText.of("tianshu.ax.event.line.mymod.ritual");

    @Test
    void kindMustBeNamespaced() {
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("death", "mymod", AXEventPayload.of(), LINE));
        assertThrows(IllegalArgumentException.class,
                () -> AXEventDescriptor.of("  ", "mymod", AXEventPayload.of(), LINE));
    }

    @Test
    void theWordingDescriptorIsRequired() {
        // The producer no longer supplies a sentence, so the contract is now "a template is required".
        // A blank *key* stays legal: it renders empty and the service falls back to the kind code.
        assertThrows(NullPointerException.class,
                () -> AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), null));
        assertEquals("", AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(),
                AXEventText.of("  ")).text().key());
    }

    @Test
    void theRenderedResultIsTreatedAsUntrustedInput() {
        // Sanitation moved to render time, so the assertion moved with it: the same input is still
        // stripped and bounded, just by the layer that produces the sentence instead of the descriptor.
        assertEquals("event开坛/event", AXEventTexts.sanitize("  <event>开坛</event>  "));
    }

    @Test
    void longTextIsBoundedWithoutCuttingInsideAWord() {
        String cleaned = AXEventTexts.sanitize("词 ".repeat(200));

        assertTrue(cleaned.length() <= AXEventTexts.MAX_TEXT_CHARS);
        assertTrue(cleaned.endsWith("词"));
    }

    @Test
    void sourceIsRecordedAndAnAnonymousProducerResolvesToTheNamedDefault() {
        AXEventDescriptor named = AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), LINE);
        AXEventDescriptor anonymous = AXEventDescriptor.of("mymod:ritual", " ", AXEventPayload.of(), LINE);

        assertEquals("mymod", named.sourceId());
        assertEquals(AXEventDescriptor.UNKNOWN_SOURCE, anonymous.sourceId());
    }

    @Test
    void theFoldShapeDefaultsToNoneAndIsCarriedVerbatim() {
        AXEventDescriptor plain = AXEventDescriptor.of("mymod:ritual", "mymod", AXEventPayload.of(), LINE);
        AXEventDescriptor foldable = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(), LINE, AXEventFold.RANGE);

        // Equivalent to the old "renderer is optional": the optional part is now the fold shape, and
        // NONE is what an event that only happens once declares.
        assertEquals(AXEventFold.NONE, plain.fold());
        assertEquals(AXEventFold.RANGE, foldable.fold());
        assertNull(plain.tier());
    }

    @Test
    void aDeclaredTierReplacesTheTableLookupWithoutTouchingAnythingElse() {
        AXEventDescriptor descriptor = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(3, 7), LINE, AXEventFold.COUNT);

        AXEventDescriptor promoted = descriptor.withTier(AXEventTier.RARE);

        assertEquals(AXEventTier.RARE, promoted.tier());
        assertEquals(descriptor.kind(), promoted.kind());
        assertEquals(descriptor.sourceId(), promoted.sourceId());
        assertSame(descriptor.text(), promoted.text());
        assertEquals(AXEventFold.COUNT, promoted.fold());
        assertNotNull(promoted.text());
        assertEquals(7, promoted.payload().magnitude());
        assertEquals(3, promoted.payload().count());
    }

    /**
     * The highlight flag is the producer's per-occurrence opinion, and it defaults to off: a producer that never thinks
     * about it gets the standing table, not an accidental whitelist.
     */
    @Test
    void highlightDefaultsToOffAndFlipsWithoutTouchingAnythingElse() {
        AXEventDescriptor descriptor = AXEventDescriptor.of(
                "mymod:ritual", "mymod", AXEventPayload.of(), LINE, AXEventFold.COUNT);

        assertFalse(descriptor.highlight());

        AXEventDescriptor highlighted = descriptor.withHighlight(true);

        assertTrue(highlighted.highlight());
        assertEquals(descriptor.kind(), highlighted.kind());
        assertEquals(descriptor.sourceId(), highlighted.sourceId());
        assertEquals(descriptor.fold(), highlighted.fold());
        assertSame(descriptor.text(), highlighted.text());
        assertFalse(highlighted.suppress());
    }
}
