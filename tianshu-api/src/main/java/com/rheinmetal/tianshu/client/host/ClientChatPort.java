package com.rheinmetal.tianshu.client.host;

/**
 * Says something as the local player, exactly as if they had typed it.
 *
 * <p>A client-side act, not a server action: chat is what any player may do, so it needs no operator, no server switch
 * and no whitelist entry. The adapter sends it through the game's own chat path, which means the server's ordinary chat
 * rules (mute, chat filters, secure profiles) apply unchanged — the model gets no privilege a player lacks.</p>
 */
public interface ClientChatPort {

    /**
     * Sends one chat line.
     *
     * @param text the line; callers trim and length-check it before asking
     * @return whether it was actually handed to the connection — {@code false} means "not connected", and the caller
     *         must report that instead of claiming the line was said
     */
    boolean say(String text);
}