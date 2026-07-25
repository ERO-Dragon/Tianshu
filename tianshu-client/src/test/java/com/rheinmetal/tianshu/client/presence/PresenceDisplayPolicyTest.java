package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.model.PresenceActivitySnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresencePrimaryState;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "tianshu.presence.status.processing_task", "正在处理任务"
        )));

        var display = policy.hudDisplay(new PresenceActivitySnapshot(
                PresencePrimaryState.PROCESSING_TASK,
                false,
                "external.mod",
                1_000L
        ));

        assertEquals("正在处理任务", display.text());
        assertEquals(PresencePrimaryState.PROCESSING_TASK, display.primaryState());
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
