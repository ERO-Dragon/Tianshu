package com.rheinmetal.tianshu.api.network;

/**
 * The one gate every outbound request passes.
 *
 * <p>Keeping this in the platform rather than in each module is the whole point: a search client and a page fetcher
 * written by different authors would otherwise each invent their own host list and their own idea of what "private
 * network" means, and one of them would get it wrong. A module supplies the request; the platform answers.</p>
 *
 * <p>The port is expected to be a runtime port. When it is missing, an outbound tool must refuse rather than fall back
 * to "no policy" — no gate means no request, not unlimited requests.</p>
 */
public interface ExternalAccessPolicy {

    /** Judges one request. Never throws for a well-formed request; a bad target is refused, not an exception. */
    ExternalDecision check(ExternalRequest request);
}