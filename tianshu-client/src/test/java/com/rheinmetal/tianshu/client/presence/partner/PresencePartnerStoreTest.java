package com.rheinmetal.tianshu.client.presence.partner;

import com.rheinmetal.tianshu.protocol.payload.PresencePartnerPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresencePartnerStoreTest {
    @Test
    void aPreWinnerHasNoLeaseAndStaysUntilSomethingReplacesIt() {
        PresencePartnerStore store = new PresencePartnerStore();

        store.accept(partner("module.maid", "maid", "tianshu:textures/gui/partner/maid.png", false, 0L, 0L));

        PresencePartnerSnapshot snapshot = store.at(5_000L);
        assertTrue(snapshot.present());
        assertEquals("module.maid", snapshot.moduleId());
        assertTrue(snapshot.showIcon());
        assertFalse(snapshot.hasLease(), "a pre-winner is not holding a lease");
        assertFalse(snapshot.showRingAt(5_000L));
    }

    @Test
    void aLeaseRunsOutByTheWallClockButOnlyTheRingEnds() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.maid", "maid", "tianshu:textures/gui/partner/maid.png", false, 11_000L, 10_000L));

        assertTrue(store.at(10_999L).showRingAt(10_999L));
        assertEquals(0.5F, store.at(6_000L).remainingFraction(6_000L), 0.001F);

        PresencePartnerSnapshot expired = store.at(11_000L);
        assertTrue(expired.present(), "the arbiter still owns the icon; only the ring ends");
        assertFalse(expired.showRingAt(11_000L));
        assertTrue(store.at(11_999L).present(), "the same participant is normally the pre-winner right after");
        assertFalse(store.at(12_001L).present(), "a silent arbiter cannot pin the marker forever");
        assertTrue(store.raw().present(), "the raw push is kept for diagnostics");
    }

    @Test
    void aDefaultOwnerWithoutAnIconIsExpressedByTheMainIcon() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.ax", "tianshu.AX", "", true, 0L, 0L));

        PresencePartnerSnapshot snapshot = store.at(1_000L);
        assertTrue(snapshot.present());
        assertTrue(snapshot.defaultOwner());
        assertFalse(snapshot.showIcon(), "the host's own assistant registers no icon, so the main icon speaks for it");
    }

    @Test
    void aDefaultOwnerWithAnIconIsDrawnLikeAnyPartner() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.maid", "maid", "tianshu:textures/gui/partner/maid.png", true, 0L, 0L));

        PresencePartnerSnapshot snapshot = store.at(1_000L);
        assertTrue(snapshot.defaultOwner());
        assertTrue(snapshot.showIcon(), "a third-party default owner is a partner the player picked, so it is visible");
    }

    @Test
    void aParticipantWithoutAnIconDecodesButDrawsNothing() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.maid", "maid", "", false, 0L, 0L));

        assertTrue(store.at(1_000L).present());
        assertFalse(store.at(1_000L).showIcon());
    }

    @Test
    void anEmptyPushHidesTheMarker() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.maid", "maid", "tianshu:textures/gui/partner/maid.png", false, 0L, 0L));

        store.accept(PresencePartnerPayload.empty("player", 2_000L));

        assertFalse(store.at(2_000L).present());
    }

    @Test
    void clearForgetsEverythingAtAWorldBoundary() {
        PresencePartnerStore store = new PresencePartnerStore();
        store.accept(partner("module.maid", "maid", "tianshu:textures/gui/partner/maid.png", false, 0L, 0L));

        store.clear();

        assertFalse(store.at(1_000L).present());
        assertFalse(store.raw().present());
    }

    private static PresencePartnerPayload partner(
            String moduleId,
            String participantId,
            String icon,
            boolean defaultOwner,
            long holdUntilMillis,
            long holdDurationMillis
    ) {
        return new PresencePartnerPayload(
                "player",
                moduleId,
                participantId,
                icon,
                defaultOwner,
                holdUntilMillis,
                holdDurationMillis,
                1_000L
        );
    }
}