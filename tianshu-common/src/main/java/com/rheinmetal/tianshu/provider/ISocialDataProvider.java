package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.ChatMessageData;

import java.util.List;

public interface ISocialDataProvider {

    List<ChatMessageData> getRecentChatMessages(int count);
}
