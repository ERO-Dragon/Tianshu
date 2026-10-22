package com.rheinmetal.tianshu.neoforge.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Build-channel switches supplied by the launcher/build task. */
public final class TianshuBuildProfile {
    private static final boolean DEBUG_BUILD = loadDebugBuild();

    private TianshuBuildProfile() {
    }

    /** Release artifacts contain a profile with this value set to false. */
    public static boolean debugBuild() {
        return DEBUG_BUILD;
    }

    private static boolean loadDebugBuild() {
        try (InputStream input = TianshuBuildProfile.class.getResourceAsStream("/tianshu-build.properties")) {
            if (input == null) {
                return false;
            }
            Properties properties = new Properties();
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty("debugBuild", "false"));
        } catch (IOException ignored) {
            return false;
        }
    }
}
