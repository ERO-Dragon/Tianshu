package com.rheinmetal.tianshu.dubhe.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheEventDescriptorTest {
    private static final DubheEventText LINE = DubheEventText.of("tianshu.dubhe.event.line.mymod.ritual");

    @Test
    void kindMustBeNamespaced() {
        assertThrows(IllegalArgumentException.class,
                () -> DubheEventDescriptor.of("death", "mymod", DubheEventPayload.of(), LINE));
        assertThrows(IllegalArgumentException.class,
                () -> DubheEventDescriptor.of("  ", "mymod", DubheEventPayload.of(), LINE));
    }

    @Test
    void theWordingDescriptorIsRequired() {
        // The producer no longer supplies a sentence, so the contract is now "a template is required".
        // A blank *key* stays legal: it renders empty and the service falls back to the kind code.
        assertThrows(NullPointerException.class,
                () -> DubheEventDescriptor.of("mymod:ritual", "mymod", DubheEventPayload.of(), null));
        assertEquals("", DubheEventDescriptor.of("mymod:ritual", "mymod", DubheEventPayload.of(),
                DubheEventText.of("  ")).text().key());
    }

    @Test
    void theRenderedResultIsTreatedAsUntrustedInput() {
        // Sanitation moved to render time, so the assertion moved with it: the same input is still
        // stripped and bounded, just by the layer that produces the sentence instead of the descriptor.
        assertEquals("event开坛/event", DubheEventTexts.sanitize("  <event>开坛</event>  "));
    }

    @Test
    void longTextIsBoundedWithoutCuttingInsideAWord() {
        String cleaned = DubheEventTexts.sanitize("词 ".repeat(200));

        assertTrue(cleaned.length() <= DubheEventTexts.MAX_TEXT_CHARS);
        assertTrue(cleaned.endsWith("词"));
    }

    @Test
    void sourceIsRecordedAndAnAnonymousProducerResolvesToTheNamedDefault() {
        DubheEventDescriptor named = DubheEventDescriptor.of("mymod:ritual", "mymod", DubheEventPayload.of(), LINE);
        DubheEventDescriptor anonymous = DubheEventDescriptor.of("mymod:ritual", " ", DubheEventPayload.of(), LINE);

        assertEquals("mymod", named.sourceId());
        assertEquals(DubheEventDescriptor.UNKNOWN_SOURCE, anonymous.sourceId());
    }

    @Test
    void theFoldShapeDefaultsToNoneAndIsCarriedVerbatim() {
        DubheEventDescriptor plain = DubheEventDescriptor.of("mymod:ritual", "mymod", DubheEventPayload.of(), LINE);
        DubheEventDescriptor foldable = DubheEventDescriptor.of(
                "mymod:ritual", "mymod", DubheEventPayload.of(), LINE, DubheEventFold.RANGE);

        // Equivalent to the old "renderer is optional": the optional part is now the fold shape, and
        // NONE is what an event that only happens once declares.
        assertEquals(DubheEventFold.NONE, plain.fold());
        assertEquals(DubheEventFold.RANGE, foldable.fold());
        assertNull(plain.tier());
    }

    @Test
    void aDeclaredTierReplacesTheTableLookupWithoutTouchingAnythingElse() {
        DubheEventDescriptor descriptor = DubheEventDescriptor.of(
                "mymod:ritual", "mymod", DubheEventPayload.of(3, 7), LINE, DubheEventFold.COUNT);

        DubheEventDescriptor promoted = descriptor.withTier(DubheEventTier.RARE);

        assertEquals(DubheEventTier.RARE, promoted.tier());
        assertEquals(descriptor.kind(), promoted.kind());
        assertEquals(descriptor.sourceId(), promoted.sourceId());
        assertSame(descriptor.text(), promoted.text());
        assertEquals(DubheEventFold.COUNT, promoted.fold());
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
        DubheEventDescriptor descriptor = DubheEventDescriptor.of(
                "mymod:ritual", "mymod", DubheEventPayload.of(), LINE, DubheEventFold.COUNT);

        assertFalse(descriptor.highlight());

        DubheEventDescriptor highlighted = descriptor.withHighlight(true);

        assertTrue(highlighted.highlight());
        assertEquals(descriptor.kind(), highlighted.kind());
        assertEquals(descriptor.sourceId(), highlighted.sourceId());
        assertEquals(descriptor.fold(), highlighted.fold());
        assertSame(descriptor.text(), highlighted.text());
        assertFalse(highlighted.suppress());
    }
}
