package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSession;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueSessionStoreTest {
    @Test
    void createClaimedCreatesActivePlayerSession() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 100L;

        var session = store.createClaimed("player", "turn", descriptor(), now);

        assertEquals(DialogueSessionState.CLAIMED, session.state());
        assertTrue(store.activeForPlayer("player", now).isPresent());
    }

    @Test
    void releaseRemovesActivePlayerSession() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 100L;
        var session = store.createClaimed("player", "turn", descriptor(), now);

        var released = store.release(session.sessionId(), DialogueReleaseReason.OWNER_COMPLETED, now + 1L);

        assertEquals(DialogueSessionState.RELEASED, released.state());
        assertTrue(store.activeForPlayer("player", now + 1L).isEmpty());
    }

    @Test
    void releaseIfPresentIsIdempotentForMissingSession() {
        DialogueSessionStore store = new DialogueSessionStore();

        var released = store.releaseIfPresent("missing", DialogueReleaseReason.MODULE_UNLOADED, 100L);

        assertTrue(released.isEmpty());
    }

    @Test
    void releaseByOwnerModuleReleasesActiveOwnedSessions() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 100L;
        var session = store.createClaimed("player", "turn", descriptor(), now);

        var released = store.releaseByOwnerModule("module.owner", DialogueReleaseReason.MODULE_UNLOADED, now + 1L);

        assertEquals(1, released.size());
        assertEquals(session.sessionId(), released.get(0).sessionId());
        assertTrue(store.activeForPlayer("player", now + 1L).isEmpty());
    }

    @Test
    void releaseByOwnerParticipantReleasesOnlyExactOwnerSessions() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 100L;
        var target = store.createClaimed("player.one", "turn.one", descriptor("module.owner", "participant.one"), now);
        var sibling = store.createClaimed("player.two", "turn.two", descriptor("module.owner", "participant.two"), now);

        var released = store.releaseByOwnerParticipant("module.owner", "participant.one", DialogueReleaseReason.MODULE_UNLOADED, now + 1L);

        assertEquals(1, released.size());
        assertEquals(target.sessionId(), released.get(0).sessionId());
        assertTrue(store.activeForPlayer("player.one", now + 1L).isEmpty());
        assertTrue(store.activeForPlayer("player.two", now + 1L).isPresent());
        assertEquals(sibling.sessionId(), store.activeForPlayer("player.two", now + 1L).orElseThrow().sessionId());
    }

    @Test
    void keepsTheSessionHistoryBoundedAndDropsTheOldestTerminalRecords() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 1_000L;
        List<String> created = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            var session = store.createClaimed("player", "turn-" + index, descriptor(), now + index);
            created.add(session.sessionId());
            store.release(session.sessionId(), DialogueReleaseReason.OWNER_COMPLETED, now + index);
        }

        String oldest = created.get(0);
        List<DialogueSession> snapshot = store.snapshot();
        assertTrue(snapshot.size() <= 256, "terminal session history is capped");
        assertFalse(snapshot.stream().anyMatch(session -> session.sessionId().equals(oldest)),
                "the oldest terminal record is evicted first");
    }

    @Test
    void pruningNeverEvictsASessionThatIsStillInFlight() {
        DialogueSessionStore store = new DialogueSessionStore();
        long now = 1_000L;
        var inFlight = store.createClaimed("player.active", "turn-active", descriptor(), now);
        for (int index = 0; index < 300; index++) {
            store.reject("player.other", "turn-" + index, now + index);
        }

        assertTrue(store.find(inFlight.sessionId()).isPresent());
        assertTrue(store.activeForPlayer("player.active", now).isPresent());
    }

    private DialogueParticipantDescriptor descriptor() {
        return descriptor("module.owner", "participant");
    }

    private DialogueParticipantDescriptor descriptor(String moduleId, String participantId) {
        return new DialogueParticipantDescriptor(participantId, moduleId, "owner", 1, com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile.DISABLED, com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup.EMPTY, "ROUTE", new DialogueTurnProcessingPolicy(1_000L, 2_000L, true));
    }
}
