package com.rheinmetal.tianshu.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The numbers the build stamped into this jar.
 *
 * <p>The platform adapter's build writes {@code /tianshu-build.properties} with the mod version and the api
 * epoch. Reading that file is what turns {@code CoreCapabilityProbe} from a set of literals into a fact an
 * external module can branch on: a probe built from a 0.2 jar says 0.2.</p>
 *
 * <p>The file is absent when the api is used outside a built jar (unit tests, a bare classpath), and absence
 * must not break anything — every getter has a documented fallback.</p>
 */
public final class BuildInfo {

    private static final String RESOURCE = "/tianshu-build.properties";
    private static final Properties PROPERTIES = load();

    private BuildInfo() {
    }

    /** The mod version this jar was built as, or {@code "unknown"} outside a built jar. */
    public static String version() {
        return property("version", "unknown");
    }

    /** The api epoch this jar was built against; derived from the mod version's MAJOR by the build. */
    public static String apiVersion() {
        return property("apiVersion", "1");
    }

    private static String property(String key, String fallback) {
        String value = PROPERTIES.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // No build profile in this classpath: the fallbacks above are the answer.
        }
        return properties;
    }
}
