package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;

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
            case "presence" -> moduleSource("tianshu-presence", "presence/settings").resolve(fileName);
            case "intake" -> moduleSource("tianshu-intake", "intake/settings").resolve(fileName);
            case "echo" -> moduleSource("tianshu-echo", "echo/settings").resolve(fileName);
            case "weaving" -> moduleSource("tianshu-weaving", "weaving/settings").resolve(fileName);
            case "dubhe" -> moduleSource("tianshu-dubhe", "dubhe/settings").resolve(fileName);
            default -> throw new IllegalArgumentException("unknown module: " + module);
        };
    }

    private static Path moduleSource(String gradleModule, String packagePath) {
        String relative = gradleModule + "/src/main/java/com/rheinmetal/tianshu/" + packagePath;
        return WorkspacePaths.find(relative).orElse(Path.of(relative));
    }
}
