package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.*;

import java.util.List;

public interface ISocialDataProvider {

    List<ChatMessageData> getRecentChatMessages(int count);

    String getPlayerName();

    String getPlayerUUID();

    boolean isChatOpen();

    List<WaypointData> getExternalWaypoints();
}
