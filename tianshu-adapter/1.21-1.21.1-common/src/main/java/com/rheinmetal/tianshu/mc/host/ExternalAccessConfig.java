package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.network.ExternalAccessPolicy;
import com.rheinmetal.tianshu.api.network.HostAllowListPolicy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads the outbound access list and publishes it as the platform's {@link ExternalAccessPolicy}.
 *
 * <p>One file, one format, both loaders — the policy is a client preference like any other, so it does not need a
 * loader-specific config system. Format in {@code config/Tianshu/external.properties}:</p>
 *
 * <pre>
 * allow=minecraft.wiki,wikipedia.org
 * allowHttp=false
 * </pre>
 *
 * <p>A missing or unreadable file means <em>nothing</em> is allowed, not everything: the failure mode of a policy must
 * be silence, never an open gate.</p>
 */
public final class ExternalAccessConfig {

    private static final String FILE = "external.properties";

    private ExternalAccessConfig() {
    }

    /** Reads the file under {@code <gameDir>/config/Tianshu/} and registers the resulting policy. */
    public static void register(Path gameDirectory) {
        TianshuRuntimePorts.register(ExternalAccessPolicy.class, load(gameDirectory));
    }

    /** The policy the file describes; {@link HostAllowListPolicy#denyAll()} when there is nothing to read. */
    public static ExternalAccessPolicy load(Path gameDirectory) {
        if (gameDirectory == null) {
            return HostAllowListPolicy.denyAll();
        }
        Path file = gameDirectory.resolve("config").resolve("Tianshu").resolve(FILE);
        if (!Files.isReadable(file)) {
            return HostAllowListPolicy.denyAll();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | RuntimeException unreadable) {
            // IllegalArgumentException included: Properties.load rejects a truncated unicode escape, which is what a
            // half-written file looks like. Escaping this into the client bootstrap failed the whole assembly — the
            // settings screen and every offline capability with it — over one bad line in an optional file.
            return HostAllowListPolicy.denyAll();
        }
        List<String> hosts = new ArrayList<>();
        for (String entry : properties.getProperty("allow", "").split(",")) {
            if (!entry.isBlank()) {
                hosts.add(entry.trim());
            }
        }
        boolean allowHttp = Boolean.parseBoolean(properties.getProperty("allowHttp", "false").trim());
        return new HostAllowListPolicy(hosts, allowHttp);
    }
}