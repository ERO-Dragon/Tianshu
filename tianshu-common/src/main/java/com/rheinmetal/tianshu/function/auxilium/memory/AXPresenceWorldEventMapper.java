package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AXPresenceWorldEventMapper {
    public AXAttachedWorldEvent map(AXScope scope, PresenceWorldEventPayload payload) {
        if (payload == null) {
            return new AXAttachedWorldEvent("", "", "", "", "", "", 0L, "", "", List.of());
        }
        AXScope effectiveScope = scope == null ? AXScope.unknown() : scope;
        Map<String, String> values = payload.values();
        String nativeId = firstNonBlank(values.get("advancementId"), payload.eventId());
        String text = eventText(payload);
        return new AXAttachedWorldEvent(
                "awe_" + payload.eventId(),
                payload.eventType(),
                dedupKey(effectiveScope, payload, nativeId, text),
                effectiveScope.worldId(),
                payload.dimensionId(),
                values.getOrDefault("position", ""),
                payload.occurredAtMillis(),
                nativeId,
                text,
                tags(payload)
        );
    }

    private String eventText(PresenceWorldEventPayload payload) {
        Map<String, String> values = payload.values();
        if (PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED.equals(payload.eventType())) {
            String title = values.getOrDefault("title", "");
            String description = values.getOrDefault("description", "");
            if (!title.isBlank() && !description.isBlank()) {
                return payload.eventType() + ": " + title + " (" + description + ")";
            }
            if (!title.isBlank()) {
                return payload.eventType() + ": " + title;
            }
        }
        String text = values.getOrDefault("text", "");
        return text.isBlank() ? payload.eventType() : text;
    }

    private String dedupKey(AXScope scope, PresenceWorldEventPayload payload, String nativeId, String text) {
        if (!nativeId.isBlank()) {
            return payload.eventType() + "\n" + scope.worldId() + "\n" + nativeId;
        }
        return payload.eventType() + "\n" + scope.worldId() + "\n" + payload.dimensionId() + "\n" + text;
    }

    private List<String> tags(PresenceWorldEventPayload payload) {
        List<String> tags = new ArrayList<>();
        tags.add("presence_world_event");
        tags.add(payload.eventType());
        String advancementId = payload.values().getOrDefault("advancementId", "");
        if (!advancementId.isBlank()) {
            tags.add(advancementId);
        }
        String type = payload.values().getOrDefault("type", "");
        if (!type.isBlank()) {
            tags.add(type);
        }
        String iconItemId = payload.values().getOrDefault("iconItemId", "");
        if (!iconItemId.isBlank()) {
            tags.add(iconItemId);
        }
        return tags;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }
}
