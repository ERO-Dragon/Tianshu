package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * How a loader puts one catalogue into one player's client.
 *
 * <p>Split from the session on purpose: <em>when</em> a catalogue can be sent is the same question on every loader
 * (the player has to have joined and announced the channel), while <em>how</em> is one line per loader. The session
 * owns the first half so a loader cannot answer it differently — the two used to: one listened for channels announced
 * after JOIN, the other only checked at JOIN and silently never sent anything to a client whose channel arrived a
 * tick later.</p>
 */
public interface ServerCatalogueChannel {

    /** Whether this player's client has announced the catalogue channel and can be sent one right now. */
    boolean ready(ServerPlayer player);

    /** Sends one catalogue. Implementations move the work to the server thread themselves. */
    void send(ServerPlayer player, List<ToolCatalogEntry> tools);
}