package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.ChatMessageData;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class RecentChatRuntimeFactProvider extends AbstractDirtyRuntimeFactProvider {
    private static final int RECENT_CHAT_COUNT = 5;
    private final WorldStateProvider worldStateProvider;

    public RecentChatRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.recent_chat";
    }

    @Override
    protected String snapshotSignature(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getSocial() == null) {
            return "recent_chat:empty";
        }
        List<ChatMessageData> messages = worldStateProvider.getSocial().getRecentChatMessages(RECENT_CHAT_COUNT);
        if (messages == null || messages.isEmpty()) {
            return "recent_chat:empty";
        }
        return messages.stream()
                .filter(message -> message != null && message.getMessageText() != null && !message.getMessageText().isBlank())
                .map(this::encode)
                .filter(encoded -> !encoded.isBlank())
                .collect(Collectors.joining("|"));
    }

    @Override
    protected List<RuntimeFact> collectFacts(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getSocial() == null) {
            return List.of();
        }
        List<ChatMessageData> messages = worldStateProvider.getSocial().getRecentChatMessages(RECENT_CHAT_COUNT);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        String value = messages.stream()
                .filter(message -> message != null && message.getMessageText() != null && !message.getMessageText().isBlank())
                .map(this::encode)
                .filter(encoded -> !encoded.isBlank())
                .collect(Collectors.joining("|"));
        if (value.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return List.of(new RuntimeFact(
                "fact.world.chat.recent",
                "recent_chat",
                providerId(),
                "chat",
                Map.of("messages", value),
                List.of("chat", "recent"),
                60,
                now,
                120_000L,
                now
        ));
    }

    private String encode(ChatMessageData message) {
        String sender = safe(message.getSenderName());
        String text = safe(message.getMessageText());
        if (text.isBlank()) {
            return "";
        }
        return sender + ";" + text;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().replace(";", " ").replace("|", " ");
    }
}
