package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed timestamp vectors.
 *
 * <p>The token is deterministic once the clock is fixed, so these values pin both halves of it — the
 * seeded SHA-256 digest and the CS64 word mix — against the behaviour the endpoint accepts. A change that
 * silently produces a well-formed but rejected token fails here instead of as a 403 in the field.</p>
 */
class EdgeTtsTokenTest {

    @Test
    void tokensMatchThePublishedVectors() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                EdgeTtsToken.generate(0L));
        assertEquals("1F5E89103EC98A08-E69D008C1241797258F0EE06F56044C11FAEC4B60F95C6AB0DB3C5E79BE2F010",
                EdgeTtsToken.generate(1_600_000_000L));
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
                EdgeTtsToken.generate(1_700_000_000L));
        assertEquals("B029A7426816F097-4579D112DC47FC3BF432B499C0A282F427C659474AF9BF5FA33CE2C003F9E9C2",
                EdgeTtsToken.generate(1_750_000_000L));
        assertEquals("745462192D9E6151-0B32CDE48BB9FCC4A5686F15B71F82E1891AEF45013623B24FB729E344645CF1",
                EdgeTtsToken.generate(1_793_612_425L));
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
                EdgeTtsToken.generate(1_800_000_000L));
    }

    @Test
    void theTimestampIsRoundedDownToFiveMinutes() {
        long fiveMinutes = 300L;
        long base = 1_800_000_000L;

        assertEquals(EdgeTtsToken.generate(base), EdgeTtsToken.generate(base + fiveMinutes - 1),
                "a token must stay valid for the whole five-minute window");
        assertTrue(!EdgeTtsToken.generate(base).equals(EdgeTtsToken.generate(base + fiveMinutes)),
                "the next window has to produce a different token");
    }

    @Test
    void theShapeIsWhatTheEndpointExpects() {
        String token = EdgeTtsToken.generate(1_800_000_000L);

        assertEquals(81, token.length(), token);
        assertTrue(token.matches("[0-9A-F]{16}-[0-9A-F]{64}"), token);
        assertTrue(EdgeTtsToken.TOKEN_VERSION.startsWith("2-"), EdgeTtsToken.TOKEN_VERSION);
    }

    @Test
    void theUserAgentIsDerivedFromTheTokenVersion() {
        String agent = EdgeTtsProtocol.userAgent(EdgeTtsToken.TOKEN_VERSION);

        assertTrue(agent.contains("Chrome/143.0.0.0"), agent);
        assertTrue(agent.contains("Edg/143.0.0.0"), agent);
    }

    @Test
    void aVersionWithoutAChromiumMajorIsRejectedInsteadOfGuessed() {
        assertThrows(IllegalArgumentException.class, () -> EdgeTtsProtocol.userAgent("v"));
    }

    @Test
    void theDefaultCredentialCarriesTheMatchingVersion() {
        EdgeTtsCredentials credentials = EdgeTtsCredentials.current();

        assertEquals(EdgeTtsToken.TOKEN_VERSION, credentials.version());
        assertTrue(credentials.token().matches("[0-9A-F]{16}-[0-9A-F]{64}"), credentials.token());
    }
}