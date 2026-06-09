package com.rheinmetal.tianshu.function.auxilium.input;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;

public final class AXDialogueInputMapper {
    public AXRequest map(DialogueDeliveryPayload delivery) {
        if (delivery == null) {
            return new AXRequest("AX.request", "", "", AXInputSource.FORWARDED);
        }
        return new AXRequest(requestKey(delivery), delivery.repairedText(), providedContext(delivery), AXInputSource.FORWARDED);
    }

    private String requestKey(DialogueDeliveryPayload delivery) {
        String sessionId = delivery.sessionId() == null || delivery.sessionId().isBlank() ? "session" : delivery.sessionId();
        String turnId = delivery.turnId() == null || delivery.turnId().isBlank() ? "turn" : delivery.turnId();
        return "AX." + sessionId + "." + turnId;
    }

    private String providedContext(DialogueDeliveryPayload delivery) {
        StringBuilder builder = new StringBuilder();
        if (!delivery.playerId().isBlank()) {
            appendLine(builder, "playerId=" + delivery.playerId());
        }
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().dimensionId().isBlank()) {
            appendLine(builder, "dimension=" + delivery.contextSnapshot().dimensionId());
        }
        if (!delivery.matchedWakeWords().isEmpty()) {
            appendLine(builder, "matchedWakeWords=" + String.join(", ", delivery.matchedWakeWords()));
        }
        if (!delivery.matchedItemIds().isEmpty()) {
            appendLine(builder, "matchedItems=" + String.join(", ", delivery.matchedItemIds()));
        }
        if (!delivery.matchedEntityRefs().isEmpty()) {
            appendLine(builder, "matchedEntities=" + delivery.matchedEntityRefs().stream()
                    .map(this::entitySummary)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (delivery.interactionHints() != null) {
            if (!delivery.interactionHints().heldItemId().isBlank()) {
                appendLine(builder, "heldItem=" + delivery.interactionHints().heldItemId());
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
        if (delivery.contextSnapshot() != null && !delivery.contextSnapshot().facts().isEmpty()) {
            delivery.contextSnapshot().facts().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    appendLine(builder, key.trim() + "=" + value.trim());
                }
            });
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

    private String entitySummary(DialogueEntityRef ref) {
        if (ref == null || ref.entityId().isBlank()) {
            return "";
        }
        if (ref.entityTypeId().isBlank()) {
            return ref.entityId();
        }
        return ref.entityTypeId() + "#" + ref.entityId();
    }
}
