package com.rheinmetal.tianshu.dubhe.module.memory;

import com.rheinmetal.tianshu.dubhe.agent.context.DubheMemoryWindowPolicy;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurnWindow;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.shared.contract.DialogueContextInputPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheDialogueContextInputMapperTest {
    private final DubheDialogueContextInputMapper mapper = new DubheDialogueContextInputMapper();
    private final DubheScope scope = new DubheScope("player", "save:Test World", "Test World", DubheScopeKind.LOCAL_WORLD, true);

    @Test
    void mapsThirdPartyUtteranceIntoAnEventWithItsSpeaker() {
        DubheRawTurn turn = mapper.map(scope, "module.example.npc", new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1"));

        assertEquals("world_event", turn.role());
        assertTrue(turn.worldEventRole());
        assertFalse(turn.assistantRole());
        assertEquals("今天矿石不够了。", turn.content());
        assertEquals("铁匠", turn.speakerName());
        assertEquals(scope.worldId(), turn.worldId());
    }

    @Test
    void usesExplicitSourceTurnIdForStableIdentity() {
        DialogueContextInputPayload payload = new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1");

        DubheRawTurn first = mapper.map(scope, "module.example.npc", payload);
        DubheRawTurn second = mapper.map(scope, "module.example.npc", payload);

        // The stable source identity is the source turn id. AX does not deduplicate context input,
        // so repeated delivery yields repeated turns; only the source identity is stable.
        assertEquals("context:module.example.npc:line-1", first.iaTurnId());
        assertEquals(first.iaTurnId(), second.iaTurnId());
        assertEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void differentSourceModulesDoNotCollideOnSameTurnId() {
        DubheRawTurn blacksmith = mapper.map(scope, "module.example.blacksmith", new DialogueContextInputPayload("铁匠", "早上好。", "greeting"));
        DubheRawTurn guard = mapper.map(scope, "module.example.guard", new DialogueContextInputPayload("卫兵", "早上好。", "greeting"));

        assertNotEquals(blacksmith.iaTurnId(), guard.iaTurnId());
        assertNotEquals(blacksmith.id(), guard.id());
    }

    @Test
    void repeatedDeliveryIsNotDeduplicatedByTheContext() {
        DubheRawTurnWindow window = new DubheRawTurnWindow(DubheMemoryWindowPolicy.DEFAULT);
        DialogueContextInputPayload payload = new DialogueContextInputPayload("铁匠", "今天矿石不够了。", "line-1");

        window.append(scope, mapper.map(scope, "module.example.npc", payload));
        window.append(scope, mapper.map(scope, "module.example.npc", payload));

        // Context input is an append-only record: callers must deliver each utterance once.
        // This test documents that guarantee explicitly instead of implying AX filters repeats.
        assertEquals(2, window.snapshot(scope).size());
    }

    @Test
    void fallsBackToContentHashWhenTurnIdIsBlank() {
        DubheRawTurn turn = mapper.map(scope, "module.example.npc", new DialogueContextInputPayload("铁匠", "今天矿石不够了。", ""));

        assertTrue(turn.iaTurnId().startsWith("context:module.example.npc:"));
        assertNotEquals("context:module.example.npc:", turn.iaTurnId());
    }
}
