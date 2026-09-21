package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityAction;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceActivityTrackerTest {
    @Test
    void listeningIsAnIndependentForegroundOverThinking() {
        AtomicLong now = new AtomicLong(1_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();

        tracker.accept("module.ax", started("ax.chat.1", PresenceActivityType.THINKING, now.get(), 30_000L));
        tracker.accept("module.asr", started("asr.speech.7", PresenceActivityType.LISTENING, now.get(), 8_000L));

        PresenceActivitySnapshot listening = tracker.snapshot();
        assertEquals(PresencePrimaryState.THINKING, listening.primaryState());
        assertTrue(listening.listening());

        tracker.accept("module.asr", ended("asr.speech.7", PresenceActivityType.LISTENING, now.get()));

        PresenceActivitySnapshot resumed = tracker.snapshot();
        assertEquals(PresencePrimaryState.THINKING, resumed.primaryState());
        assertFalse(resumed.listening());
    }

    @Test
    void primaryStateUsesProductPriorityWithoutCancellingLowerActivities() {
        AtomicLong now = new AtomicLong(2_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();

        tracker.accept("module.llm", started("llm.model.load", PresenceActivityType.LOADING, now.get(), 30_000L));
        tracker.accept("external.tasks", started("external.task.1", PresenceActivityType.PROCESSING_TASK, now.get(), 30_000L));
        tracker.accept("module.ax", started("ax.chat.1", PresenceActivityType.THINKING, now.get(), 30_000L));
        tracker.accept("module.ax", started("ax.chat.1", PresenceActivityType.RESPONDING, now.get(), 30_000L));

        assertEquals(PresencePrimaryState.RESPONDING, tracker.snapshot().primaryState());

        tracker.accept("module.ax", ended("ax.chat.1", PresenceActivityType.RESPONDING, now.get()));
        assertEquals(PresencePrimaryState.THINKING, tracker.snapshot().primaryState());

        tracker.accept("module.ax", ended("ax.chat.1", PresenceActivityType.THINKING, now.get()));
        assertEquals(PresencePrimaryState.PROCESSING_TASK, tracker.snapshot().primaryState());
    }

    @Test
    void reservedStatesRejectUntrustedSources() {
        AtomicLong now = new AtomicLong(3_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();

        tracker.accept("external.mod", started("fake.chat", PresenceActivityType.THINKING, now.get(), 30_000L));
        tracker.accept("external.mod", started("fake.reply", PresenceActivityType.RESPONDING, now.get(), 30_000L));
        tracker.accept("external.mod", started("fake.speech", PresenceActivityType.LISTENING, now.get(), 30_000L));

        PresenceActivitySnapshot snapshot = tracker.snapshot();
        assertEquals(PresencePrimaryState.IDLE, snapshot.primaryState());
        assertFalse(snapshot.listening());
    }

    @Test
    void concurrentTasksEndIndependentlyAndExpireAsFallback() {
        AtomicLong now = new AtomicLong(4_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();

        tracker.accept("module.ax", started("task.a", PresenceActivityType.PROCESSING_TASK, now.get(), 500L));
        tracker.accept("module.ax", started("task.b", PresenceActivityType.PROCESSING_TASK, now.get(), 2_000L));
        tracker.accept("module.ax", ended("task.a", PresenceActivityType.PROCESSING_TASK, now.get()));

        assertEquals(PresencePrimaryState.PROCESSING_TASK, tracker.snapshot().primaryState());

        now.set(6_001L);
        assertEquals(PresencePrimaryState.IDLE, tracker.snapshot().primaryState());
    }

    @Test
    void worldRestartRejectsEventsCreatedBeforeTheNewSession() {
        AtomicLong now = new AtomicLong(10_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();
        tracker.accept("module.ax", started("old.task", PresenceActivityType.PROCESSING_TASK, now.get(), 30_000L));

        tracker.stopWorldSession();
        now.set(20_000L);
        tracker.startWorldSession();
        tracker.accept("module.ax", started("late.old.task", PresenceActivityType.PROCESSING_TASK, 15_000L, 30_000L));

        assertEquals(PresencePrimaryState.IDLE, tracker.snapshot().primaryState());
    }

    @Test
    void unchangedSnapshotsAreCachedUntilAnActivityChangesOrExpires() {
        AtomicLong now = new AtomicLong(30_000L);
        PresenceActivityTracker tracker = new PresenceActivityTracker(now::get);
        tracker.startWorldSession();
        tracker.accept("module.ax", started("ax.chat.1", PresenceActivityType.THINKING, now.get(), 5_000L));

        PresenceActivitySnapshot first = tracker.snapshot();
        now.set(31_000L);
        PresenceActivitySnapshot unchanged = tracker.snapshot();

        assertTrue(first == unchanged);

        now.set(35_001L);
        PresenceActivitySnapshot expired = tracker.snapshot();
        assertFalse(expired == unchanged);
        assertEquals(PresencePrimaryState.IDLE, expired.primaryState());
    }

    private static PresenceActivityPayload started(
            String id,
            PresenceActivityType type,
            long occurredAtMillis,
            long ttlMillis
    ) {
        return new PresenceActivityPayload(id, type, PresenceActivityAction.STARTED, occurredAtMillis, ttlMillis);
    }

    private static PresenceActivityPayload ended(String id, PresenceActivityType type, long occurredAtMillis) {
        return new PresenceActivityPayload(id, type, PresenceActivityAction.ENDED, occurredAtMillis, 0L);
    }
}
