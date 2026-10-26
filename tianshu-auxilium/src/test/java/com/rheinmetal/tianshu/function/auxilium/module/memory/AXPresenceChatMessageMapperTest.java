package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

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
class AXPresenceChatMessageMapperTest {

    private static final AXScope SCOPE =
            new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);

    private final AXPresenceChatMessageMapper mapper = new AXPresenceChatMessageMapper();

    @Test
    void aChatEventBecomesAWorldEventTurnWithItsSpeaker() {
        PresenceWorldEventPayload event = PresenceWorldEventPayload.chatMessage("uuid-1", "村民甲", "有人在家吗");

        AXRawTurn turn = mapper.map(SCOPE, event);

        assertEquals("村民甲", turn.speakerName());
        assertEquals("有人在家吗", turn.content());
        assertTrue(turn.worldEventRole(), "a chat line reads as something that happened, not as a turn of ours");
    }

    @Test
    void theSameWordsFromTwoSendersStayTwoLines() {
        AXRawTurn first = mapper.map(SCOPE, PresenceWorldEventPayload.chatMessage("uuid-1", "甲", "在吗"));
        AXRawTurn second = mapper.map(SCOPE, PresenceWorldEventPayload.chatMessage("uuid-2", "乙", "在吗"));

        assertNotEquals(first.id(), second.id(), "the sender is part of the identity, not only the words");
    }

    @Test
    void theSameLineProjectsToTheSameIdentity() {
        AXRawTurn first = mapper.map(SCOPE, PresenceWorldEventPayload.chatMessage("uuid-1", "甲", "在吗"));
        AXRawTurn second = mapper.map(SCOPE, PresenceWorldEventPayload.chatMessage("uuid-1", "甲", "在吗"));

        assertEquals(first.id(), second.id(), "a redelivery of one line must not read as two");
    }
}
