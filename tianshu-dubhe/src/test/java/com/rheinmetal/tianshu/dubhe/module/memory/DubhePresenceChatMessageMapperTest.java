package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chat line reaches history as an event, which is what the merged presence payload makes possible.
 *
 * <p>The earlier shape carried chat as its own payload; the module then had two input kinds to keep in step.
 * Now the event type says "someone spoke" and these checks pin the projection: the speaker is preserved, the
 * words are preserved, and two people saying the same thing stay two lines.</p>
 */
class DubhePresenceChatMessageMapperTest {

    private static final DubheScope SCOPE =
            new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);

    private final DubhePresenceChatMessageMapper mapper = new DubhePresenceChatMessageMapper();

    @Test
    void aChatEventBecomesAWorldEventTurnWithItsSpeaker() {
        PresenceWorldEventPayload event = chat("uuid-1", "村民甲", "有人在家吗");

        DubheRawTurn turn = mapper.map(SCOPE, event);

        assertEquals("村民甲", turn.speakerName());
        assertEquals("有人在家吗", turn.content());
        assertTrue(turn.worldEventRole(), "a chat line reads as something that happened, not as a turn of ours");
    }

    @Test
    void theSameWordsFromTwoSendersStayTwoLines() {
        DubheRawTurn first = mapper.map(SCOPE, chat("uuid-1", "甲", "在吗"));
        DubheRawTurn second = mapper.map(SCOPE, chat("uuid-2", "乙", "在吗"));

        assertNotEquals(first.id(), second.id(), "the sender is part of the identity, not only the words");
    }

    @Test
    void theSameLineProjectsToTheSameIdentity() {
        // A redelivery carries the event it is a redelivery of, timestamp included: two payloads built a
        // millisecond apart are two events, not one repeated line.
        PresenceWorldEventPayload line = chat("uuid-1", "甲", "在吗");
        DubheRawTurn first = mapper.map(SCOPE, line);
        DubheRawTurn second = mapper.map(SCOPE, line);

        assertEquals(first.id(), second.id(), "a redelivery of one line must not read as two");
    }

    private static PresenceWorldEventPayload chat(String senderId, String senderName, String text) {
        return new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_CHAT_MESSAGE,
                senderId, "", 1L, Map.of(
                        PresenceWorldEventPayload.VALUE_SENDER_ID, senderId,
                        PresenceWorldEventPayload.VALUE_SENDER_NAME, senderName,
                        PresenceWorldEventPayload.VALUE_TEXT, text));
    }
}
