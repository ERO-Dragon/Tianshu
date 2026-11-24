package com.rheinmetal.tianshu.protocol;

/** Wire epochs are independent of the mod release. Unchanged payloads retain their channel version. */
public final class ProtocolWireVersion {

    public static final String LEGACY_VERSION = "3";
    public static final String VERSION = "4";

    private ProtocolWireVersion() {
    }
}
