package com.rheinmetal.tianshu.api.network;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Allows only hosts on the list, and never an address on this machine's own network.
 *
 * <p>An empty list allows nothing: the default has to be "no outbound traffic", otherwise every tool that forgets to be
 * configured is quietly allowed to send the player's words anywhere.</p>
 *
 * <p>A listed host covers itself and its subdomains ({@code wikipedia.org} covers {@code en.wikipedia.org}) — the
 * practical unit for a site, not for an API. Literal addresses on a private or loopback network are refused even when
 * listed, because "the user allowed wikipedia.org" is not a statement about {@code 10.0.0.5}.</p>
 *
 * <p>What this cannot see: a public host whose DNS answer points at a private address, and redirects to unlisted hosts.
 * Refusing the first needs a resolver hook; the second is the calling module's job, and its contract says so.</p>
 */
public final class HostAllowListPolicy implements ExternalAccessPolicy {

    private final Set<String> allowedHosts;
    private final boolean allowHttp;

    public HostAllowListPolicy(Collection<String> allowedHosts, boolean allowHttp) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (allowedHosts != null) {
            for (String host : allowedHosts) {
                if (host == null) {
                    continue;
                }
                String trimmed = host.trim().toLowerCase(Locale.ROOT);
                if (trimmed.startsWith(".")) {
                    trimmed = trimmed.substring(1);
                }
                if (trimmed.endsWith(".")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                if (!trimmed.isEmpty()) {
                    cleaned.add(trimmed);
                }
            }
        }
        this.allowedHosts = Set.copyOf(cleaned);
        this.allowHttp = allowHttp;
    }

    /** The policy a platform has before anyone configures it: everything is refused. */
    public static HostAllowListPolicy denyAll() {
        return new HostAllowListPolicy(Set.of(), false);
    }

    @Override
    public ExternalDecision check(ExternalRequest request) {
        if (request == null) {
            return ExternalDecision.deny("no request to judge");
        }
        String scheme = request.url().getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !allowHttp) {
            return ExternalDecision.deny("plain http is not allowed; only https leaves this machine");
        }
        String host = request.host();
        if (isLocal(host)) {
            return ExternalDecision.deny(host + " is on this machine's own network, which no outbound request may reach");
        }
        if (allowedHosts.isEmpty()) {
            return ExternalDecision.deny("no host is allowed yet; add one to the external access list first");
        }
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return ExternalDecision.allow();
            }
        }
        return ExternalDecision.deny(host + " is not on the external access list");
    }

    private static boolean isLocal(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        if (host.contains(":")) {
            // IPv6 literal: loopback, link-local and unique-local are all off limits.
            return host.equals("::1") || host.equals("[::1]") || host.startsWith("fe80")
                    || host.startsWith("fc") || host.startsWith("fd");
        }
        int[] octets = ipv4(host);
        if (octets == null) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 127
                || octets[0] == 0
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127);
    }

    private static int[] ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException notAnAddress) {
                return null;
            }
            if (octets[index] < 0 || octets[index] > 255) {
                return null;
            }
        }
        return octets;
    }

    /** The hosts this policy allows, for a diagnostics screen to show rather than guess. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Whether plain http is admitted at all. */
    public boolean allowsHttp() {
        return allowHttp;
    }
}