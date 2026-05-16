package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsSessionTest {
    @Test
    void terminalSessionCannotBeCompletedAfterCancel() {
        TtsSession session = new TtsSession(request("request-1"));

        session.cancel("stopped");
        session.complete();

        assertEquals(TtsSessionState.CANCELLED, session.state());
        assertEquals(TtsFailureCode.CANCELLED, session.failure().code());
        assertTrue(session.isTerminal());
    }

    @Test
    void terminalSessionCannotBeFailedAfterComplete() {
        TtsSession session = new TtsSession(request("request-1"));

        session.complete();
        session.fail(TtsFailure.of(TtsFailureCode.SYNTHESIS_FAILED, "failed"));

        assertEquals(TtsSessionState.COMPLETED, session.state());
        assertTrue(session.isTerminal());
    }

    @Test
    void transitionDoesNotOverrideTerminalState() {
        TtsSession session = new TtsSession(request("request-1"));

        session.cancel("stopped");
        session.transition(TtsSessionState.PLAYING);

        assertEquals(TtsSessionState.CANCELLED, session.state());
    }

    private static TtsRequest request(String requestId) {
        return new TtsRequest(
                requestId,
                requestId,
                requestId,
                "hello",
                TtsRequestSource.ASSISTANT,
                TtsPlaybackPolicy.QUEUE,
                Priority.NORMAL,
                TtsVoiceProfile.defaults(),
                false
        );
    }
}
