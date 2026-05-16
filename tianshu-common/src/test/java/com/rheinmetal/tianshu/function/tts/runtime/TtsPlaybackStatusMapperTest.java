package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TtsPlaybackStatusMapperTest {
    @Test
    void mapsInternalStatesToExternalPlaybackPhase() {
        TtsRequest request = new TtsRequest(
                "req-1",
                "env-1",
                "trace-1",
                "hello",
                TtsRequestSource.ASSISTANT,
                TtsPlaybackPolicy.QUEUE,
                Priority.NORMAL,
                TtsVoiceProfile.defaults(),
                false
        );
        TtsSession session = new TtsSession(request);

        assertEquals(TtsPlaybackPhase.ACCEPTED, TtsPlaybackStatusMapper.phaseOf(session));

        session.transition(TtsSessionState.SYNTHESIZING);
        assertEquals(TtsPlaybackPhase.STARTED, TtsPlaybackStatusMapper.phaseOf(session));

        session.transition(TtsSessionState.PLAYING);
        assertEquals(TtsPlaybackPhase.SPEAKING, TtsPlaybackStatusMapper.phaseOf(session));

        session.transition(TtsSessionState.DRAINING);
        assertEquals(TtsPlaybackPhase.DRAINING, TtsPlaybackStatusMapper.phaseOf(session));

        session.transition(TtsSessionState.COMPLETED);
        assertEquals(TtsPlaybackPhase.COMPLETED, TtsPlaybackStatusMapper.phaseOf(session));
    }
}
