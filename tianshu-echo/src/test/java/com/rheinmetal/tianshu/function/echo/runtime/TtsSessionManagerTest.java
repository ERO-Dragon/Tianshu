package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsSessionManagerTest {
    /**
     * A session is created per sentence and carries the whole text, so keeping terminal ones made this the one
     * structure that grew for as long as the player kept talking.
     */
    @Test
    void terminalSessionsDoNotStayInTheManager() {
        TtsSessionManager manager = new TtsSessionManager();
        TtsSession completed = manager.create(request("completed"));
        TtsSession cancelled = manager.create(request("cancelled"));
        TtsSession live = manager.create(request("live"));

        manager.complete(completed);
        manager.cancel("cancelled", "stop");

        assertEquals(1, manager.liveCount(), "only the session that can still change may remain");
        assertFalse(manager.cancel("cancelled", "stop again").isPresent(),
                "a cancelled session has nothing left to stop");
        manager.complete(live);
        assertEquals(0, manager.liveCount());
    }

    @Test
    void cancelAllCancelsOnlyNonTerminalSessions() {
        TtsSessionManager manager = new TtsSessionManager();
        TtsSession queued = manager.create(request("queued"));
        TtsSession playing = manager.create(request("playing"));
        TtsSession completed = manager.create(request("completed"));
        queued.transition(TtsSessionState.QUEUED);
        playing.transition(TtsSessionState.PLAYING);
        completed.complete();
        manager.activate(playing);

        List<TtsSession> cancelled = manager.cancelAll("stop all");

        assertEquals(2, cancelled.size());
        assertEquals(TtsSessionState.CANCELLED, queued.state());
        assertEquals(TtsSessionState.CANCELLED, playing.state());
        assertEquals(TtsSessionState.COMPLETED, completed.state());
        assertFalse(manager.active().isPresent());
    }

    @Test
    void cancelDoesNotChangeTerminalSession() {
        TtsSessionManager manager = new TtsSessionManager();
        TtsSession session = manager.create(request("request-1"));
        session.complete();

        assertFalse(manager.cancel("request-1", "stop").isPresent());
        assertEquals(TtsSessionState.COMPLETED, session.state());
    }

    @Test
    void activateIgnoresTerminalSession() {
        TtsSessionManager manager = new TtsSessionManager();
        TtsSession session = manager.create(request("request-1"));
        session.cancel("stop");

        manager.activate(session);

        assertFalse(manager.active().isPresent());
    }

    private static TtsRequest request(String requestId) {
        return new TtsRequest(
                requestId,
                requestId,
                requestId,
                requestId,
                "hello",
                TtsRequestSource.of("module.ax"),
                TtsPlaybackPolicy.QUEUE,
                Priority.NORMAL,
                TtsVoiceProfile.defaults()
        );
    }
}
