package com.rheinmetal.tianshu.function.intake.repair.enhance;

import com.rheinmetal.tianshu.function.intake.repair.core.IRParseResult;
import com.rheinmetal.tianshu.function.intake.repair.core.ParseUnit;
import com.rheinmetal.tianshu.function.intake.repair.input.IrPreparedInput;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Output of the named-object enhancement of one utterance.
 *
 * <p>It is two facts, not four parallel lists: the sentence as it should be read (with the object words
 * the ASR misheard repaired and the rest of the utterance untouched) and the objects that were matched.
 * Every match carries its own spoken word and registry id, which is what keeps the pairing correct when
 * an id has no display name or two ids share one name.</p>
 *
 * @param repairedText the whole utterance with matched object words repaired; never a resource id
 * @param matches one entry per matched object, in match order
 */
public record IrNamedObjectEnhancementResult(String repairedText, List<IrObjectMatch> matches) {
    public IrNamedObjectEnhancementResult {
        if (repairedText == null) {
            repairedText = "";
        }
        repairedText = repairedText.trim();
        matches = matches == null ? List.of() : List.copyOf(matches);
    }

    /**
     * Builds paired matches from one parse result.
     *
     * <p>Pairing happens per id instead of being reconstructed later from two parallel lists: a blank
     * display name drops only that one object, and two ids that share a display name stay two matches
     * with their own ids.</p>
     */
    public static List<IrObjectMatch> matchesFrom(
            IRParseResult parseResult,
            Function<String, String> itemDisplayNames,
            Function<String, String> entityDisplayNames
    ) {
        if (parseResult == null) {
            return List.of();
        }
        Set<String> itemIds = new LinkedHashSet<>(parseResult.getMatchedItemRealIds());
        for (ParseUnit unit : parseResult.getUnits()) {
            if (unit != null && unit.targetRealItemId != null && !unit.targetRealItemId.isBlank()) {
                itemIds.add(unit.targetRealItemId.trim());
            }
        }
        List<IrObjectMatch> matches = new ArrayList<>();
        for (String itemId : itemIds) {
            addMatch(matches, itemDisplayNames, itemId, true);
        }
        for (String entityId : new LinkedHashSet<>(parseResult.getMatchedEntityTypeIds())) {
            addMatch(matches, entityDisplayNames, entityId, false);
        }
        return List.copyOf(matches);
    }

    private static void addMatch(
            List<IrObjectMatch> matches,
            Function<String, String> displayNames,
            String registryId,
            boolean item
    ) {
        if (registryId == null || registryId.isBlank()) {
            return;
        }
        String id = registryId.trim();
        String displayName = displayNames.apply(id);
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        matches.add(item
                ? IrObjectMatch.item(displayName.trim(), id)
                : IrObjectMatch.entity(displayName.trim(), id));
    }

    public static IrNamedObjectEnhancementResult empty(String repairedText) {
        return new IrNamedObjectEnhancementResult(repairedText, List.of());
    }

    /** Whether any named object was matched; the list is the answer, this only names the question. */
    public boolean matched() {
        return !matches.isEmpty();
    }
}