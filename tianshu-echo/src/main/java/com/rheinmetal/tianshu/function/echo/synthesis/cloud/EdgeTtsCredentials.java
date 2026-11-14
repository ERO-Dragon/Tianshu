package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

/**
 * The credential pair one Edge attempt needs.
 *
 * <p>Both halves travel together because they are derived together: the token is only valid for the version
 * string it was computed for, so passing them separately is where a mismatched pair would creep in.</p>
 */
public record EdgeTtsCredentials(String token, String version) {

    /** The credential for the current five-minute window, with the version its token was derived for. */
    public static EdgeTtsCredentials current() {
        return new EdgeTtsCredentials(EdgeTtsToken.generateNow(), EdgeTtsToken.TOKEN_VERSION);
    }

    public EdgeTtsCredentials {
        token = token == null ? "" : token.trim();
        version = version == null ? "" : version.trim();
        if (token.isEmpty() || version.isEmpty()) {
            throw new IllegalArgumentException("both the token and its version are required");
        }
    }
}