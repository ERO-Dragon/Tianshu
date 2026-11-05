package com.rheinmetal.tianshu.protocol;

/**
 * The wire epoch of the one network channel the platform owns.
 *
 * <p>The version is part of the channel registration on both loaders: a client and a server that disagree refuse
 * each other's channel, which is the whole point of having one. It used to be a literal in every adapter — 43
 * copies of the same string, one per loader and Minecraft line — so a protocol bump meant forty-three edits and a
 * missed copy was a silent handshake failure. The literal now lives here, and
 * {@code verifyProtocolVersionSingleSource} keeps it that way.</p>
 *
 * <p>This is the epoch of the envelope/payload wire shape, not of the api surface: an adapter is free to change
 * how it registers the channel, and must not be free to change what is on it.</p>
 */
public final class ProtocolWireVersion {

    /** Bumped whenever a payload changes shape or the payload set changes incompatibly. */
    public static final String VERSION = "3";

    private ProtocolWireVersion() {
    }
}