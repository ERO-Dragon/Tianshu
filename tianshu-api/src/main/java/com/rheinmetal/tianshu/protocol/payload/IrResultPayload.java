package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * What the repair step heard and what it matched.
 *
 * <p>The evidence is the two match lists, and nothing else: an object match names the words the player used next to
 * the registry id they resolved to, and a module match names the words that fired a module next to the word that
 * module registered. There is no parallel id list and no id-to-name map, because both restated part of what a match
 * already carries and let a reader miss the pairing.</p>
 *
 * @param repairedText the corrected utterance a prompt should show
 * @param normalizedText the utterance reduced to comparable units, for matching only
 * @param rawText the utterance as the recogniser produced it, before any repair
 * @param objectMatches every named object (item, creature, block, dimension, biome, effect, …) that was matched
 * @param moduleMatches every module trigger word that was hit
 */
public record IrResultPayload(
        String repairedText,
        String normalizedText,
        String rawText,
        List<IrObjectMatch> objectMatches,
        List<IrModuleMatch> moduleMatches,
        int turnId,
        long sessionId,
        long timestampMillis
) implements ITianshuPayload {
    public IrResultPayload {
        if (repairedText == null) repairedText = "";
        repairedText = repairedText.trim();
        if (normalizedText == null) normalizedText = "";
        normalizedText = normalizedText.trim();
        rawText = rawText == null ? "" : rawText.trim();
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        moduleMatches = moduleMatches == null ? List.of() : List.copyOf(moduleMatches);
        sessionId = Math.max(0L, sessionId);
        timestampMillis = timestampMillis > 0L ? timestampMillis : System.currentTimeMillis();
    }
}
