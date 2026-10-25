package com.rheinmetal.tianshu.protocol;

import com.rheinmetal.tianshu.protocol.payload.CancelPayload;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payload vocabulary is open: a module registers its own kind without editing the kernel.
 *
 * <p>The kernel only guarantees naming and uniqueness. That guarantee is what lets a module live in its
 * own jar: it asks for a name, gets the canonical instance for it, and nobody has to ship a kernel release
 * to carry the new kind.</p>
 */
class PayloadTypeRegistryTest {

    @Test
    void unknownNameIsRegisteredAndAlwaysResolvesToTheSameInstance() {
        PayloadType first = PayloadType.of("TEST_DYNAMIC_KIND");
        PayloadType second = PayloadType.of("  TEST_DYNAMIC_KIND  ");

        assertTrue(PayloadType.isRegistered("TEST_DYNAMIC_KIND"));
        assertSame(first, second);
        assertEquals("TEST_DYNAMIC_KIND", first.name());
    }

    @Test
    void identityFollowsTheNameNotTheInstance() {
        assertEquals(PayloadType.of("TEST_IDENTITY_KIND"), PayloadType.of("TEST_IDENTITY_KIND"));
        assertNotEquals(PayloadType.TEXT, PayloadType.of("TEST_OTHER_KIND"));
    }

    @Test
    void aNameBindsToExactlyOnePayloadClass() {
        PayloadType bound = PayloadType.of("TEST_BOUND_KIND", TextPayload.class);

        assertSame(TextPayload.class, PayloadType.payloadClassOf("TEST_BOUND_KIND"));
        assertSame(bound, PayloadType.of("TEST_BOUND_KIND", TextPayload.class));

        assertThrows(IllegalStateException.class,
                () -> PayloadType.of("TEST_BOUND_KIND", CancelPayload.class));
    }

    @Test
    void blankNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PayloadType.of("   "));
        assertThrows(IllegalArgumentException.class, () -> PayloadType.of(null));
    }

    @Test
    void shippedConstantsAreRegisteredAndListed() {
        assertTrue(PayloadType.values().contains(PayloadType.TEXT));
        assertTrue(PayloadType.values().contains(PayloadType.CUSTOM));
        assertFalse(PayloadType.values().isEmpty());
        assertTrue(PayloadType.isRegistered(PayloadType.CUSTOM.name()));
    }
}
