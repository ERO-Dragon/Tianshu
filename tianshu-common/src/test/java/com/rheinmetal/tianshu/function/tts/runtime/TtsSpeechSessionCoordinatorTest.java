package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsSpeechSessionCoordinatorTest {
    @Test
    void existingSessionIsNotAdmittedAgain() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        TtsSpeechSessionKey key = key("module.ax", 1L);

        assertEquals(TtsSpeechSessionCoordinator.AdmissionState.ACCEPTED,
                coordinator.admit(key, TtsPlaybackPlacement.QUEUE_AFTER_SESSION).state());
        assertEquals(TtsSpeechSessionCoordinator.AdmissionState.EXISTING,
                coordinator.admit(key, TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY).state());
        assertFalse(coordinator.admit(key, TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY).cancelledSentence());
    }

    @Test
    void nestedSentenceInterruptionResumesCThenBThenA() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        TtsSpeechSessionKey a = key("a", 1L);
        TtsSpeechSessionKey b = key("b", 2L);
        TtsSpeechSessionKey c = key("c", 3L);

        coordinator.admit(a, TtsPlaybackPlacement.QUEUE_AFTER_SESSION);
        coordinator.appendSentence(a, "A1");
        coordinator.appendSentence(a, "A2");
        coordinator.end(a);
        TtsSpeechSessionCoordinator.SentenceWork a1 = coordinator.poll().orElseThrow();
        assertEquals("A1", a1.text());

        coordinator.admit(b, TtsPlaybackPlacement.INSERT_AFTER_SENTENCE);
        coordinator.appendSentence(b, "B1");
        coordinator.appendSentence(b, "B2");
        coordinator.appendSentence(b, "B3");
        coordinator.end(b);
        coordinator.complete(a1);

        TtsSpeechSessionCoordinator.SentenceWork b1 = coordinator.poll().orElseThrow();
        assertEquals("B1", b1.text());
        coordinator.complete(b1);
        TtsSpeechSessionCoordinator.SentenceWork b2 = coordinator.poll().orElseThrow();
        assertEquals("B2", b2.text());

        TtsSpeechSessionCoordinator.Admission admission = coordinator.admit(
                c,
                TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY
        );
        assertTrue(admission.cancelledSentence());
        assertEquals(b2, admission.cancelledWork());
        coordinator.appendSentence(c, "C1");
        coordinator.end(c);

        TtsSpeechSessionCoordinator.SentenceWork c1 = coordinator.poll().orElseThrow();
        assertEquals("C1", c1.text());
        coordinator.complete(c1);
        TtsSpeechSessionCoordinator.SentenceWork b3 = coordinator.poll().orElseThrow();
        assertEquals("B3", b3.text());
        coordinator.complete(b3);
        TtsSpeechSessionCoordinator.SentenceWork a2 = coordinator.poll().orElseThrow();
        assertEquals("A2", a2.text());
    }

    @Test
    void dropIfBusyDropsWholeNewSession() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        coordinator.admit(key("a", 1L), TtsPlaybackPlacement.QUEUE_AFTER_SESSION);

        TtsSpeechSessionCoordinator.Admission result = coordinator.admit(
                key("b", 2L),
                TtsPlaybackPlacement.DROP_IF_BUSY
        );

        assertEquals(TtsSpeechSessionCoordinator.AdmissionState.DROPPED, result.state());
    }

    @Test
    void sentenceBoundaryInsertionRemainsAttachedToAdmissionTarget() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        TtsSpeechSessionKey a = key("a", 1L);
        TtsSpeechSessionKey b = key("b", 2L);
        TtsSpeechSessionKey c = key("c", 3L);

        coordinator.admit(a, TtsPlaybackPlacement.QUEUE_AFTER_SESSION);
        coordinator.appendSentence(a, "A1");
        coordinator.appendSentence(a, "A2");
        coordinator.end(a);
        assertEquals("A1", coordinator.poll().orElseThrow().text());

        coordinator.admit(b, TtsPlaybackPlacement.INSERT_AFTER_SENTENCE);
        coordinator.appendSentence(b, "B1");
        coordinator.end(b);
        coordinator.admit(c, TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY);
        coordinator.appendSentence(c, "C1");
        coordinator.appendSentence(c, "C2");
        coordinator.end(c);

        TtsSpeechSessionCoordinator.SentenceWork c1 = coordinator.poll().orElseThrow();
        assertEquals("C1", c1.text());
        coordinator.complete(c1);
        TtsSpeechSessionCoordinator.SentenceWork c2 = coordinator.poll().orElseThrow();
        assertEquals("C2", c2.text());
        coordinator.complete(c2);
        TtsSpeechSessionCoordinator.SentenceWork b1 = coordinator.poll().orElseThrow();
        assertEquals("B1", b1.text());
        coordinator.complete(b1);
        assertEquals("A2", coordinator.poll().orElseThrow().text());
    }

    @Test
    void cancellingSuspendedSessionPromotesItsAfterSessionChildren() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        TtsSpeechSessionKey a = key("a", 1L);
        TtsSpeechSessionKey b = key("b", 2L);
        TtsSpeechSessionKey c = key("c", 3L);

        coordinator.admit(a, TtsPlaybackPlacement.QUEUE_AFTER_SESSION);
        coordinator.appendSentence(a, "A1");
        assertEquals("A1", coordinator.poll().orElseThrow().text());
        coordinator.admit(b, TtsPlaybackPlacement.INSERT_AFTER_SESSION);
        coordinator.appendSentence(b, "B1");
        coordinator.end(b);
        coordinator.admit(c, TtsPlaybackPlacement.CANCEL_SENTENCE_AND_PLAY);
        coordinator.appendSentence(c, "C1");
        coordinator.end(c);

        assertTrue(coordinator.cancel(a));
        TtsSpeechSessionCoordinator.SentenceWork c1 = coordinator.poll().orElseThrow();
        assertEquals("C1", c1.text());
        coordinator.complete(c1);
        assertEquals("B1", coordinator.poll().orElseThrow().text());
    }

    @Test
    void urgentPlacementsShareTheSameSessionCapacity() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        assertEquals(TtsSpeechSessionCoordinator.AdmissionState.ACCEPTED,
                coordinator.admit(key("active", 1L), TtsPlaybackPlacement.QUEUE_AFTER_SESSION).state());
        for (int index = 0; index < 8; index++) {
            assertEquals(TtsSpeechSessionCoordinator.AdmissionState.ACCEPTED,
                    coordinator.admit(key("session-" + index, index + 2L),
                            TtsPlaybackPlacement.INSERT_AFTER_SENTENCE).state());
        }

        assertEquals(TtsSpeechSessionCoordinator.AdmissionState.REJECTED,
                coordinator.admit(key("overflow", 10L),
                        TtsPlaybackPlacement.CANCEL_SESSION_AND_PLAY).state());
    }

    @Test
    void cancellingAttachedChildRemovesParentReferenceAndEmitsOneTermination() {
        TtsSpeechSessionCoordinator coordinator = new TtsSpeechSessionCoordinator();
        TtsSpeechSessionKey a = key("a", 1L);
        TtsSpeechSessionKey b = key("b", 2L);

        coordinator.admit(a, TtsPlaybackPlacement.QUEUE_AFTER_SESSION);
        coordinator.appendSentence(a, "A1");
        coordinator.end(a);
        TtsSpeechSessionCoordinator.SentenceWork a1 = coordinator.poll().orElseThrow();
        coordinator.admit(b, TtsPlaybackPlacement.INSERT_AFTER_SESSION);
        coordinator.appendSentence(b, "B1");
        coordinator.end(b);

        assertTrue(coordinator.cancel(b));
        assertEquals(List.of(new TtsSpeechSessionCoordinator.Termination(
                b,
                TtsSpeechSessionCoordinator.TerminationReason.CANCELLED
        )), coordinator.drainTerminations());
        coordinator.complete(a1);
        assertTrue(coordinator.poll().isEmpty());
        assertEquals(List.of(new TtsSpeechSessionCoordinator.Termination(
                a,
                TtsSpeechSessionCoordinator.TerminationReason.COMPLETED
        )), coordinator.drainTerminations());
    }

    private static TtsSpeechSessionKey key(String source, long sessionId) {
        return TtsSpeechSessionKey.of(source, sessionId, 1, source);
    }
}
