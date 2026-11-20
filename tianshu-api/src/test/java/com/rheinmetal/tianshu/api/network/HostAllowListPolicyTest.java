package com.rheinmetal.tianshu.api.network;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound gate: nothing leaves this machine unless a host is listed, and never a private address.
 *
 * <p>These are the rules a module must not be trusted to reimplement — an allowlist that forgot {@code 10.0.0.0/8}, or a
 * scheme check that only looked at the prefix, is exactly how a search tool becomes an SSRF.</p>
 */
class HostAllowListPolicyTest {

    private static final String WIKI = "https://minecraft.wiki/";

    @Test
    void anEmptyListRefusesEverything() {
        ExternalDecision decision = HostAllowListPolicy.denyAll().check(request(WIKI));

        assertFalse(decision.allowed(), "no configuration means no outbound traffic");
        assertTrue(decision.reason().contains("no host is allowed"));
    }

    @Test
    void aListedHostAndItsSubdomainsAreAllowed() {
        var policy = new HostAllowListPolicy(List.of("wikipedia.org"), false);

        assertTrue(policy.check(request("https://wikipedia.org/wiki/X")).allowed());
        assertTrue(policy.check(request("https://en.wikipedia.org/wiki/X")).allowed());
        assertFalse(policy.check(request("https://example.com/")).allowed());
        assertFalse(policy.check(request("https://notwikipedia.org/")).allowed(),
                "a subdomain check must respect the dot, or every attacker-chosen name passes");
    }

    @Test
    void plainHttpIsRefusedUnlessThePolicySaysOtherwise() {
        var strict = new HostAllowListPolicy(List.of("minecraft.wiki"), false);
        var lenient = new HostAllowListPolicy(List.of("minecraft.wiki"), true);

        assertFalse(strict.check(request("http://minecraft.wiki/")).allowed());
        assertTrue(lenient.check(request("http://minecraft.wiki/")).allowed());
    }

    @Test
    void addressesOnThisMachinesOwnNetworkAreRefusedEvenWhenListed() {
        var policy = new HostAllowListPolicy(
                List.of("10.0.0.5", "127.0.0.1", "192.168.1.10", "169.254.1.1", "172.20.0.1", "100.64.0.1", "localhost"),
                false);

        for (String target : List.of("https://10.0.0.5/", "https://127.0.0.1:8080/", "https://192.168.1.10/",
                "https://169.254.1.1/", "https://172.20.0.1/", "https://100.64.0.1/", "https://localhost/")) {
            assertFalse(policy.check(request(target)).allowed(), "must be refused: " + target);
            assertTrue(policy.check(request(target)).reason().contains("own network"), target);
        }
    }

    @Test
    void aPublicAddressOnTheListIsAllowed() {
        var policy = new HostAllowListPolicy(List.of("93.184.216.34"), false);

        assertTrue(policy.check(request("https://93.184.216.34/")).allowed());
    }

    @Test
    void theTargetHasToBeAnAbsoluteHttpUrlToBeJudgedAtAll() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalRequest(ExternalRequest.Kind.FETCH, URI.create("file:///etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalRequest(ExternalRequest.Kind.FETCH, URI.create("/relative")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalRequest(ExternalRequest.Kind.FETCH, URI.create("ftp://example.com/")));
    }

    @Test
    void theListIsNormalisedSoATrailingDotOrCaseDoesNotChangeTheAnswer() {
        var policy = new HostAllowListPolicy(Set.of("Minecraft.Wiki"), false);

        assertTrue(policy.check(request("https://minecraft.wiki./")).allowed());
        assertTrue(policy.allowedHosts().contains("minecraft.wiki"));
    }

    private static ExternalRequest request(String url) {
        return new ExternalRequest(ExternalRequest.Kind.FETCH, URI.create(url));
    }
}