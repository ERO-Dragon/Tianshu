package com.rheinmetal.tianshu.function.tts.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TtsRequestSourceTest {
    @Test
    void acceptsArbitraryProtocolModuleId() {
        assertEquals("other.npc", TtsRequestSource.of("other.npc").value());
        assertEquals(TtsRequestSource.of("other.npc"), TtsRequestSource.from(" other.npc "));
    }

    @Test
    void rejectsBlankSourceId() {
        assertThrows(IllegalArgumentException.class, () -> TtsRequestSource.of(" "));
    }
}
