package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.function.presence.status.PresenceDisplayPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceDisplayPolicyTest {
    @Test
    void listeningTextTakesForegroundWithoutReplacingPrimaryState() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.listening", "正在聆听",
                "tianshu.presence.status.responding", "正在回复"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.RESPONDING,
                true,
                false,
                "module.ax",
                1_000L
        ));

        assertEquals("正在聆听", display.text());
        assertEquals(PresencePrimaryState.RESPONDING, display.primaryState());
        assertTrue(display.listening());
    }

    @Test
    void primaryStateUsesStableLocalizedKey() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.loading", "正在加载"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.LOADING,
                false,
                false,
                "module.llm",
                1_000L
        ));

        assertEquals("正在加载", display.text());
        assertEquals(PresencePrimaryState.LOADING, display.primaryState());
    }

    @Test
    void backgroundWorkIsCarriedAsAParallelMarkNotAsWords() {
        PresenceDisplayPolicy policy = new PresenceDisplayPolicy(texts(Map.of(
                "tianshu.presence.status.thinking", "正在思考"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.THINKING,
                false,
                true,
                "module.ax",
                1_000L
        ));

        assertEquals("正在思考", display.text());
        assertTrue(display.backgroundTask());
        assertFalse(display.listening());
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