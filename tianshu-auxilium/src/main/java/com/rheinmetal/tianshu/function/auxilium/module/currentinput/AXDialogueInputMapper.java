package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;

public final class AXDialogueInputMapper {
    public AXRequest map(DialogueDeliveryPayload delivery) {
        if (delivery == null) {
            return new AXRequest("AX.request", "", "", AXInputSource.FORWARDED);
        }
        return new AXRequest(requestKey(delivery), delivery.repairedText(), deliverySnapshot(delivery), AXInputSource.FORWARDED);
    }

    private String requestKey(DialogueDeliveryPayload delivery) {
        String sessionId = delivery.sessionId() == null || delivery.sessionId().isBlank() ? "session" : delivery.sessionId();
        String turnId = delivery.turnId() == null || delivery.turnId().isBlank() ? "turn" : delivery.turnId();
        return "AX." + sessionId + "." + turnId;
    }

    private String deliverySnapshot(DialogueDeliveryPayload delivery) {
        StringBuilder builder = new StringBuilder();
        if (!delivery.playerId().isBlank()) {
            appendLine(builder, "playerId=" + delivery.playerId());
        }
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().dimensionId().isBlank()) {
            appendLine(builder, "dimension=" + readable(delivery, delivery.contextSnapshot().dimensionId()));
        }
        // What the player actually said about a matched object is the best name to show: it is already in their
        // language and in their words. A payload with no object matches simply has no line, instead of falling back
        // to a registry id the model would have to decode.
        String matchedObjects = matchedObjectWords(delivery);
        if (!matchedObjects.isBlank()) {
            appendLine(builder, "matchedObjects=" + matchedObjects);
        }
        if (!delivery.matchedEntityRefs().isEmpty()) {
            appendLine(builder, "matchedEntities=" + delivery.matchedEntityRefs().stream()
                    .map(ref -> entitySummary(delivery, ref))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (delivery.interactionHints() != null) {
            if (!delivery.interactionHints().heldItemId().isBlank()) {
                appendLine(builder, "heldItem=" + readable(delivery, delivery.interactionHints().heldItemId()));
            }
            if (delivery.interactionHints().crosshairHit()) {
                appendLine(builder, "crosshairHit=true");
            }
            if (delivery.interactionHints().interactionKeyDown()) {
                appendLine(builder, "interactionKeyDown=true");
            }
            if (delivery.interactionHints().targetDistance() > 0.0D) {
                appendLine(builder, "targetDistance=" + delivery.interactionHints().targetDistance());
            }
        }
        // Only which facts are available, never their values: the values reach the model as rendered prose in
        // the game-context section, and repeating them here as a machine dump would teach the model a data
        // format it has no use for while doubling the tokens those facts cost.
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().facts().isEmpty()) {
            String factIds = delivery.contextSnapshot().facts().keySet().stream()
                    .filter(key -> key != null && !key.isBlank())
                    .map(String::trim)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            if (!factIds.isBlank()) {
                appendLine(builder, "facts=" + factIds);
            }
        }
        return builder.toString().trim();
    }

    private void appendLine(StringBuilder builder, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    /**
     * Display name, then a readable form of the id, then nothing.
     *
     * <p>The one place this degeneration exists: the platform resolves a localized name and hands it over in
     * {@link DialogueDeliveryPayload#displayNames()}, and whatever it could not resolve degrades here to the id's own
     * words. A raw {@code namespace:path} is never shown to the model: a player hears "diamond sword", so the model
     * should read the same thing.</p>
     */
    private String readable(DialogueDeliveryPayload delivery, String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String trimmed = id.trim();
        String display = displayName(delivery, trimmed);
        return display.isBlank() ? readableId(trimmed) : display;
    }

    private String displayName(DialogueDeliveryPayload delivery, String id) {
        if (delivery.displayNames() == null) {
            return "";
        }
        String display = delivery.displayNames().get(id);
        return display == null ? "" : display.trim();
    }

    /** {@code minecraft:diamond_sword} becomes {@code diamond sword}: keep the words, drop the machine syntax. */
    private String readableId(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        return path.replace('_', ' ').replace('/', ' ').trim();
    }

    /** The words the player used for the matched objects, in match order and without repeats. */
    private static String matchedObjectWords(DialogueDeliveryPayload delivery) {
        if (delivery == null || delivery.objectMatches() == null || delivery.objectMatches().isEmpty()) {
            return "";
        }
        return delivery.objectMatches().stream()
                .map(match -> match.spokenWord())
                .filter(word -> word != null && !word.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * What the player is looking at, named the way they would name it.
     *
     * <p>The entity's instance id is deliberately absent: it identifies which zombie this is, not what it is, and a UUID
     * in the prompt is noise the model cannot use. When neither a display name nor a readable type id is available the
     * entry is dropped rather than filled with a machine identifier.</p>
     */
    private String entitySummary(DialogueDeliveryPayload delivery, DialogueEntityRef ref) {
        if (ref == null) {
            return "";
        }
        String display = ref.displayName() == null ? "" : ref.displayName().trim();
        return display.isBlank() ? readable(delivery, ref.entityTypeId()) : display;
    }
}
