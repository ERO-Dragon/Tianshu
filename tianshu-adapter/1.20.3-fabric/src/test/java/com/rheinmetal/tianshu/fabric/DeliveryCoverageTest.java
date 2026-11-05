package com.rheinmetal.tianshu.fabric;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shared entry point is delivered by this loader, or is a declared gap.
 *
 * <p>The shared layer owns the decisions; a loader adapter only forwards. A forwarding call that is missing is
 * invisible: the product still builds, still starts, and one capability is simply dead on that loader. This test
 * turns the forwarding table into something the build checks.</p>
 *
 * <p>An entry point listed in {@link #DECLARED_GAPS} must also <em>not</em> be referenced — implementing one
 * without updating the list is a failure, so the gap list cannot quietly go stale.</p>
 */
final class DeliveryCoverageTest {
    private static final Path SHARED_EVENTS =
            Path.of("../1.21-1.21.1-common/src/main/java/com/rheinmetal/tianshu/mc/event/ClientEvents.java");
    private static final List<Path> ADAPTER = List.of(
            Path.of("src/main/java/com/rheinmetal/tianshu/fabric/FabricClientEvents.java"),
            Path.of("src/main/java/com/rheinmetal/tianshu/fabric/FabricClientBootstrap.java"));

    /** Entry points this loader has no native event for. Each one is a fact its consumers never receive here. */
    private static final Set<String> DECLARED_GAPS = Set.of("onPlayerClone");

    @Test
    void everySharedEntryPointIsForwardedOrADeclaredGap() throws Exception {
        Set<String> entryPoints = entryPointsOf(Files.readString(SHARED_EVENTS, StandardCharsets.UTF_8));
        String adapterSources = adapterSources();

        assertFalse(entryPoints.isEmpty(), "no entry points were found in the shared event layer");
        TreeSet<String> missing = new TreeSet<>();
        for (String entryPoint : entryPoints) {
            boolean forwarded = adapterSources.contains("events." + entryPoint + "(");
            if (DECLARED_GAPS.contains(entryPoint)) {
                assertFalse(forwarded, () -> entryPoint + " is declared a gap but is now forwarded; "
                        + "remove it from DECLARED_GAPS so the table keeps telling the truth");
            } else if (!forwarded) {
                missing.add(entryPoint);
            }
        }
        assertTrue(missing.isEmpty(), "this loader never forwards: " + missing);
    }

    private static Set<String> entryPointsOf(String source) {
        Pattern pattern = Pattern.compile("public void (\\w+)\\(");
        Matcher matcher = pattern.matcher(source);
        Set<String> names = new TreeSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        names.remove("resetVoiceInputState");
        return names;
    }

    private static String adapterSources() throws Exception {
        StringBuilder sources = new StringBuilder();
        for (Path path : ADAPTER) {
            sources.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
        }
        return sources.toString();
    }
}