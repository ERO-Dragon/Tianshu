package com.rheinmetal.tianshu.function.intake.repair;

import com.rheinmetal.tianshu.function.intake.repair.input.IrInputText;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrTriggerMatcherTest {

    @Test
    void singleCharacterWakeWordDoesNotMatchAcrossSyllableBoundaries() {
        List<IrCompiledTrigger> index = compile(new TriggerRegistration("module.love", List.of("爱"), List.of()));

        assertTrue(match("开门", index).isEmpty(), "kai must not fire the wake word ai");
        assertTrue(match("来玩", index).isEmpty(), "lai must not fire the wake word ai");
        assertEquals(List.of("爱"), match("爱你", index));
    }

    @Test
    void multiCharacterTriggerMatchesItsOwnTokenRun() {
        List<IrCompiledTrigger> index = compile(new TriggerRegistration("module.maid", List.of("酒狐"), List.of()));

        assertEquals(List.of("酒狐"), match("帮我找酒狐", index));
        assertTrue(match("只说了酒", index).isEmpty());
        // Homophones are the point of pinyin matching: 九狐 tokenizes to the same jiu+hu run.
        assertEquals(List.of("酒狐"), match("九狐在哪里", index));
    }

    private List<IrCompiledTrigger> compile(TriggerRegistration registration) {
        return new IrTriggerIndexer().compile(List.of(registration));
    }

    private List<String> match(String text, List<IrCompiledTrigger> index) {
        return new IrTriggerMatcher().match(input(text), index).matches().stream()
                .map(match -> match.registeredWord())
                .toList();
    }

    private IrInputText input(String text) {
        return new IrInputText(text, text, 1, 1L, "test", 100L);
    }
}