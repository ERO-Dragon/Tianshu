package com.rheinmetal.tianshu.api.network;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * One request to leave this machine, described well enough for the platform to judge it.
 *
 * <p>The target is a value here, not a string: a caller that has not parsed its URL has not decided anything yet. The
 * constructor refuses anything that is not an absolute http(s) URL with a host, so an empty or relative target cannot
 * reach a policy and be "allowed" by accident.</p>
 *
 * @param kind what the caller intends to do; a search endpoint and an arbitrary page fetch are not the same risk
 * @param url  the absolute target
 */
public record ExternalRequest(Kind kind, URI url) {

    public enum Kind {
        /** Calls a search provider's own endpoint. */
        SEARCH,
        /** Fetches one page the model asked for. */
        FETCH,
        /** Downloads a known artifact from a fixed distribution host. */
        DOWNLOAD
    }

    public ExternalRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(url, "url");
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("only http and https can leave this machine, not " + scheme);
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new IllegalArgumentException("an outbound request needs a host: " + url);
        }
    }

    /** The host as written, lowercased and without a port or trailing dot. */
    public String host() {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    @Override
    public String toString() {
        return kind + " " + url;
    }
}