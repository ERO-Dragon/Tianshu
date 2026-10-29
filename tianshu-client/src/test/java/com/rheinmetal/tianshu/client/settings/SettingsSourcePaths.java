package com.rheinmetal.tianshu.client.settings;

import java.nio.file.Path;

/**
 * Locates a module's settings page source.
 *
 * <p>A page lives in the module that owns the settings it edits, so a guardrail that spans several
 * pages has to reach across module directories. Keeping the mapping in one place means a page that
 * moves again only changes this file.</p>
 */
final class SettingsSourcePaths {

    private SettingsSourcePaths() {
    }

    /**
     * @param moduleAndFile module key and file name, e.g. {@code "intake/IntakeSettingsRegistrySource.java"}
     */
    static Path page(String moduleAndFile) {
        int slash = moduleAndFile.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("expected <module>/<file>: " + moduleAndFile);
        }
        return page(moduleAndFile.substring(0, slash), moduleAndFile.substring(slash + 1));
    }

    static Path page(String module, String fileName) {
        return switch (module) {
            case "client" -> moduleSource("tianshu-client", "client/settings/module").resolve(fileName);
            case "presence" -> moduleSource("tianshu-presence", "function/presence/settings").resolve(fileName);
            case "intake" -> moduleSource("tianshu-intake", "function/intake/settings").resolve(fileName);
            case "echo" -> moduleSource("tianshu-echo", "function/echo/settings").resolve(fileName);
            case "weaving" -> moduleSource("tianshu-weaving", "function/weaving/settings").resolve(fileName);
            case "auxilium" -> moduleSource("tianshu-auxilium", "function/auxilium/settings").resolve(fileName);
            default -> throw new IllegalArgumentException("unknown module: " + module);
        };
    }

    private static Path moduleSource(String gradleModule, String packagePath) {
        return Path.of("..", gradleModule, "src", "main", "java", "com", "rheinmetal", "tianshu")
                .resolve(packagePath);
    }
}
