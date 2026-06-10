package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrWakeWordEnhancerTest {

    @Test
    void repairsHighConfidenceWakeWordBeforeMatching() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of())
        ));
        IrInputText input = new IrInputText("九狐帮我种地", "九狐帮我种地", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);
        IrMatchBatch batch = new IrVoiceTriggerMatcher().match(repaired, index);

        assertEquals("酒狐帮我种地", repaired.text());
        assertEquals(List.of("酒狐"), batch.matches().get(0).matchedWakeWords());
    }

    @Test
    void keepsTextWhenWakeWordCandidateIsTooWeak() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of())
        ));
        IrInputText input = new IrInputText("帮我种地", "帮我种地", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);

        assertEquals("帮我种地", repaired.text());
    }

    @Test
    void keepsTextWhenOverlappingCandidatesAreAmbiguous() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.left", List.of("酒狐"), List.of()),
                new VoiceTriggerRegistration("module.right", List.of("九狐"), List.of())
        ));
        IrInputText input = new IrInputText("久狐帮我种地", "久狐帮我种地", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);

        assertEquals("久狐帮我种地", repaired.text());
    }

    @Test
    void prefersLongestWakeWordWhenCandidatesContainEachOther() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.short", List.of("酒狐"), List.of()),
                new VoiceTriggerRegistration("module.long", List.of("酒狐女仆"), List.of())
        ));
        IrInputText input = new IrInputText("九狐女仆帮我种地", "九狐女仆帮我种地", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);
        IrMatchBatch batch = new IrVoiceTriggerMatcher().match(repaired, index);

        assertEquals("酒狐女仆帮我种地", repaired.text());
        assertTrue(batch.matches().stream()
                .filter(match -> match.moduleId().equals("module.long"))
                .anyMatch(match -> match.matchedWakeWords().equals(List.of("酒狐女仆"))));
    }

    @Test
    void repairsMultipleNonOverlappingWakeWordsConservatively() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of()),
                new VoiceTriggerRegistration("module.create", List.of("机械动力"), List.of())
        ));
        IrInputText input = new IrInputText("九狐和机械动利都在吗", "九狐和机械动利都在吗", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);
        IrMatchBatch batch = new IrVoiceTriggerMatcher().match(repaired, index);

        assertEquals("酒狐和机械动力都在吗", repaired.text());
        assertTrue(batch.matches().stream().map(IrVoiceMatch::moduleId).toList().containsAll(List.of("module.maid", "module.create")));
    }

    @Test
    void doesNotUseExtraWordsAsRepairTargets() {
        IrVoiceTriggerIndexer indexer = new IrVoiceTriggerIndexer();
        IrWakeWordEnhancer enhancer = new IrWakeWordEnhancer();
        List<IrCompiledVoiceTrigger> index = indexer.compile(List.of(
                new VoiceTriggerRegistration("module.maid", List.of("酒狐"), List.of("种地"))
        ));
        IrInputText input = new IrInputText("九狐帮我重地", "九狐帮我重地", 1, 2L, "asr", 100L);

        IrInputText repaired = enhancer.enhance(input, index);

        assertEquals("酒狐帮我重地", repaired.text());
    }
}
