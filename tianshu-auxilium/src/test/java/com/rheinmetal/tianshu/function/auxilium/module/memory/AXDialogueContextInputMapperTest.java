package com.rheinmetal.tianshu.function.auxilium.module.memory;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnWindow;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueContextInputPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXDialogueContextInputMapperTest {
    private final AXDialogueContextInputMapper mapper = new AXDialogueContextInputMapper();
    private final AXScope scope = new AXScope("player", "save:Test World", "Test World", AXScopeKind.LOCAL_WORLD, true);

    @Test
    void mapsThirdPartyUtteranceIntoGameChatTurn() {
        AXRawTurn turn = mapper.map(scope, "module.example.npc", new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1"));

        assertEquals("game_chat", turn.role());
        assertTrue(turn.gameChatRole());
        assertFalse(turn.assistantRole());
        assertEquals("今天矿石不够了。", turn.content());
        assertEquals("铁匠", turn.speakerName());
        assertEquals(scope.worldId(), turn.worldId());
    }

    @Test
    void usesExplicitSourceTurnIdForStableIdentity() {
        DialogueContextInputPayload payload = new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1");

        AXRawTurn first = mapper.map(scope, "module.example.npc", payload);
        AXRawTurn second = mapper.map(scope, "module.example.npc", payload);

        // The stable source identity is the source turn id. AX does not deduplicate context input,
        // so repeated delivery yields repeated turns; only the source identity is stable.
        assertEquals("context:module.example.npc:line-1", first.iaTurnId());
        assertEquals(first.iaTurnId(), second.iaTurnId());
        assertEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void differentSourceModulesDoNotCollideOnSameTurnId() {
        AXRawTurn blacksmith = mapper.map(scope, "module.example.blacksmith", new DialogueContextInputPayload("铁匠", "早上好。", "greeting"));
        AXRawTurn guard = mapper.map(scope, "module.example.guard", new DialogueContextInputPayload("卫兵", "早上好。", "greeting"));

        assertNotEquals(blacksmith.iaTurnId(), guard.iaTurnId());
        assertNotEquals(blacksmith.id(), guard.id());
    }

    @Test
    void repeatedDeliveryIsNotDeduplicatedByTheContext() {
        AXRawTurnWindow window = new AXRawTurnWindow(AXMemoryWindowPolicy.DEFAULT);
        DialogueContextInputPayload payload = new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1");

        window.append(scope, mapper.map(scope, "module.example.npc", payload));
        window.append(scope, mapper.map(scope, "module.example.npc", payload));

        // Context input is an append-only record: callers must deliver each utterance once.
        // This test documents that guarantee explicitly instead of implying AX filters repeats.
        assertEquals(2, window.snapshot(scope).size());
    }

    @Test
    void fallsBackToContentHashWhenTurnIdIsBlank() {
        AXRawTurn turn = mapper.map(scope, "module.example.npc", new DialogueContextInputPayload("铁匠", "今天矿石不够了。", ""));

        assertTrue(turn.iaTurnId().startsWith("context:module.example.npc:"));
        assertNotEquals("context:module.example.npc:", turn.iaTurnId());
    }
}
