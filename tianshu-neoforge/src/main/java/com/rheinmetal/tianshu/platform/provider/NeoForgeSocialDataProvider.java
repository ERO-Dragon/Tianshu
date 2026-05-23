package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.ISocialDataProvider;
import com.rheinmetal.tianshu.snapshot.ChatMessageData;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class NeoForgeSocialDataProvider implements ISocialDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CHAT_HISTORY = 50;

    private final Deque<ChatMessageData> chatHistory = new ConcurrentLinkedDeque<>();

    public NeoForgeSocialDataProvider() {
        NeoForge.EVENT_BUS.addListener(this::onChatReceived);
    }

    private void onChatReceived(ClientChatReceivedEvent event) {
        try {
            Component message = event.getMessage();
            if (message == null) return;

            String messageText = message.getString();
            if (messageText == null || messageText.isEmpty()) return;

            String senderName = extractSender(messageText);
            chatHistory.addLast(new ChatMessageData(senderName, messageText));

            while (chatHistory.size() > MAX_CHAT_HISTORY) {
                chatHistory.removeFirst();
            }
        } catch (Exception e) {
            LOGGER.warn("记录聊天消息失败: {}", e.getMessage());
        }
    }

    private String extractSender(String messageText) {
        if (messageText == null || messageText.isEmpty()) return "System";
        int ltIdx = messageText.indexOf('<');
        int gtIdx = messageText.indexOf('>');
        if (ltIdx == 0 && gtIdx > ltIdx) {
            return messageText.substring(1, gtIdx);
        }
        return "System";
    }

    @Override
    public List<ChatMessageData> getRecentChatMessages(int count) {
        if (count <= 0) return Collections.emptyList();

        List<ChatMessageData> all = new ArrayList<>(chatHistory);
        if (all.size() <= count) return all;

        return all.subList(all.size() - count, all.size());
    }

}
