package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.function.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.function.presence.status.PresenceDisplayPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceDisplayPolicyTest {
    @Test
    void listeningIsWordedAsItsOwnPrimaryState() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.listening", "listening-text",
                "tianshu.presence.status.responding", "responding-text"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.LISTENING,
                false,
                "module.asr",
                1_000L
        ));

        assertEquals("listening-text", display.text());
        assertEquals(PresencePrimaryState.LISTENING, display.primaryState());
    }

    @Test
    void primaryStateUsesStableLocalizedKey() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.loading", "loading-text"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.LOADING,
                false,
                "module.llm",
                1_000L
        ));

        assertEquals("loading-text", display.text());
        assertEquals(PresencePrimaryState.LOADING, display.primaryState());
    }

    @Test
    void backgroundWorkIsCarriedAsAParallelMarkNotAsWords() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.thinking", "thinking-text"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.THINKING,
                true,
                "module.ax",
                1_000L
        ));

        assertEquals("thinking-text", display.text());
        assertTrue(display.backgroundTask());
    }

    private static PresenceTextProvider texts(Map<String, String> values) {
        return new PresenceTextProvider() {
            @Override
            public boolean exists(String key) {
                return values.containsKey(key);
            }

            @Override
            public String text(String key, Object... args) {
                return values.getOrDefault(key, "");
            }
        };
    }
}
