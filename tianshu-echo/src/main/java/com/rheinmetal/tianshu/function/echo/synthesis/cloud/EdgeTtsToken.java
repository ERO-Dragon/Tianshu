package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

/**
 * The v2 {@code Sec-MS-GEC} token Edge's endpoint requires.
 *
 * <p>The endpoint answers 403 to the plain SHA-256 token that older clients sent. What it accepts is a
 * derivation published by the reverse-engineered client: a SHA-256 variant whose initial state is seeded from
 * the first seven characters of the message, plus a "CS64" word mix, joined as
 * {@code <16 hex>-<64 hex>}. The constants below are the algorithm's parameters; the implementation here is
 * written against the published behaviour and verified against fixed test vectors rather than copied.</p>
 *
 * <p>The token depends only on the clock (rounded to five minutes) and the fixed trusted-client id, so it is
 * deterministic and testable: {@link #generate(long)} takes the timestamp instead of reading the clock.</p>
 */
public final class EdgeTtsToken {

    /** Seconds between the Unix epoch and the Windows file-time epoch (1601-01-01). */
    private static final long WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L;

    /** The endpoint expects the timestamp rounded down to five minutes. */
    private static final long ROUNDING_SECONDS = 300L;

    private static final int MASK = 0xFFFF_FFFF;

    /** The token identifies "the read-aloud consumer"; it is not an account. */
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    /** The Chromium build whose token derivation this implementation follows. */
    public static final String TOKEN_VERSION = "2-143.0.3650.75";

    private static final int[] SHA256_INITIAL = {
            0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
            0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };

    private static final int[] SEED_STATE = {
            0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
            0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    };

    private static final int[] SEED_SCHEDULE = {
            0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA,
            0xA622EE8C, 0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6,
            0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
            0xD258B060, 0x72766564,
    };

    private static final int[] SHA256_ROUND_CONSTANTS = {
            0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5,
            0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
            0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
            0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
            0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC,
            0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
            0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
            0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
            0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
            0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
            0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3,
            0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
            0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
            0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
            0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
            0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    };

    /** The seed block replaces round constant 14 with this value. */
    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;

    private static final int SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static final int[][] CS64_COEFFICIENTS = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D}, {0x3BEB65BC, 0x29514038},
            {0xC89C5933, 0xB328CBDF}, {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB}, {0x06064CF3, 0xFA51A3DA},
            {0xDA79FA60, 0x207CC464}, {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167},
    };

    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJUSTMENT0 = 0xF2F90B1A;
    private static final int CS64_ADJUSTMENT1 = 0x0B1A0000;

    private EdgeTtsToken() {
    }

    /**
     * @param unixTimestampSeconds wall-clock seconds, injectable so the token is reproducible in a test
     * @return {@code "<16 hex>-<64 hex>"}
     */
    public static String generate(long unixTimestampSeconds) {
        long ticks = unixTimestampSeconds + WINDOWS_EPOCH_OFFSET_SECONDS;
        ticks -= ticks % ROUNDING_SECONDS;
        ticks *= 10_000_000L;
        byte[] data = (ticks + TRUSTED_CLIENT_TOKEN).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        int[] cs64 = cs64Digest(data, digest);
        // The two CS64 words are published as little-endian bytes, not as big-endian hex words.
        byte[] cs64Bytes = new byte[8];
        for (int index = 0; index < 4; index++) {
            cs64Bytes[index] = (byte) (cs64[0] >>> (8 * index));
            cs64Bytes[4 + index] = (byte) (cs64[1] >>> (8 * index));
        }
        return toHex(cs64Bytes) + "-" + toHex(digest);
    }

    /** The token for the current clock. */
    public static String generateNow() {
        return generate(System.currentTimeMillis() / 1000L);
    }

    // ---------------------------------------------------------------- sha-256 variant

    private static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) {
            throw new IllegalArgumentException("token data must contain at least seven bytes");
        }
        long length = 57L + data.length;
        int paddingBytes = (int) ((55L - length) % 64L + 64L) % 64;
        byte[] padded = new byte[data.length - 7 + 1 + paddingBytes + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bitLength = length * 8L;
        for (int index = 0; index < 8; index++) {
            padded[padded.length - 1 - index] = (byte) (bitLength >>> (8 * index));
        }
        int[] state = sha256Init(data);
        for (int offset = 0; offset < padded.length; offset += 64) {
            int[] words = new int[64];
            for (int index = 0; index < 16; index++) {
                int base = offset + index * 4;
                words[index] = ((padded[base] & 0xFF) << 24)
                        | ((padded[base + 1] & 0xFF) << 16)
                        | ((padded[base + 2] & 0xFF) << 8)
                        | (padded[base + 3] & 0xFF);
            }
            expandSchedule(words, 16);
            int[] working = sha256Rounds(state, words, 0);
            for (int index = 0; index < 8; index++) {
                state[index] = state[index] + working[index];
            }
        }
        byte[] digest = new byte[32];
        for (int index = 0; index < 8; index++) {
            digest[index * 4] = (byte) (state[index] >>> 24);
            digest[index * 4 + 1] = (byte) (state[index] >>> 16);
            digest[index * 4 + 2] = (byte) (state[index] >>> 8);
            digest[index * 4 + 3] = (byte) state[index];
        }
        return digest;
    }

    /** The seeded initial state, computed from the first seven bytes of the message. */
    private static int[] sha256Init(byte[] data) {
        int[] words = new int[31];
        for (int index = 0; index < 14; index++) {
            words[index] = SEED_SCHEDULE[index];
        }
        words[14] = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        words[15] = ((data[3] & 0xFF) << 24) | ((data[4] & 0xFF) << 16)
                | ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
        for (int index = 16; index < 30; index++) {
            int value = SEED_SCHEDULE[index - 16];
            if (index - 2 >= 14) {
                value += (index == 16)
                        ? (sigma1(words[index - 2]) ^ SIGMA1_ADJUSTMENT)
                        : sigma1(words[index - 2]);
            }
            if (index - 7 >= 14) {
                value += words[index - 7];
            }
            if (index - 15 >= 14) {
                value += (index == 29)
                        ? (sigma0(words[index - 15]) ^ SIGMA0_ADJUSTMENT)
                        : sigma0(words[index - 15]);
            }
            words[index] = value;
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14];
        int[] schedule = new int[64];
        System.arraycopy(words, 0, schedule, 0, 30);
        schedule[30] = words[30];
        expandSchedule(schedule, 31);
        int[] working = sha256Rounds(SEED_STATE, schedule, 14);
        int[] state = new int[8];
        for (int index = 0; index < 8; index++) {
            state[index] = SHA256_INITIAL[index] + working[index];
        }
        return state;
    }

    private static void expandSchedule(int[] words, int firstWord) {
        for (int index = firstWord; index < 64; index++) {
            int x = words[index - 2];
            int y = words[index - 15];
            words[index] = sigma1(x) + words[index - 7] + sigma0(y) + words[index - 16];
        }
    }

    private static int[] sha256Rounds(int[] state, int[] words, int firstRound) {
        int[] constants = firstRound == 14 ? blockConstants() : SHA256_ROUND_CONSTANTS;
        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];
        int e = state[4];
        int f = state[5];
        int g = state[6];
        int h = state[7];
        for (int index = firstRound; index < 64; index++) {
            int t1 = h + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11)
                    ^ Integer.rotateRight(e, 25)) + (g ^ (e & (f ^ g)))
                    + constants[index] + words[index];
            int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13)
                    ^ Integer.rotateRight(a, 22)) + ((a & b) | (c & (a | b)));
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    private static int[] blockConstantsCache;

    private static int[] blockConstants() {
        int[] cached = blockConstantsCache;
        if (cached != null) {
            return cached;
        }
        int[] constants = SHA256_ROUND_CONSTANTS.clone();
        constants[14] = SHA256_ADJUSTMENT;
        blockConstantsCache = constants;
        return constants;
    }

    private static int sigma0(int value) {
        return Integer.rotateRight(value, 7) ^ Integer.rotateRight(value, 18) ^ (value >>> 3);
    }

    private static int sigma1(int value) {
        return Integer.rotateRight(value, 17) ^ Integer.rotateRight(value, 19) ^ (value >>> 10);
    }

    // ---------------------------------------------------------------- cs64

    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = readLittleEndianInt(digest, 0) | 1;
        int keyB = readLittleEndianInt(digest, 4) | 1;
        int[] factors = {keyA * CS64_FACTOR, keyB * CS64_FACTOR};

        int coefficientCount = CS64_COEFFICIENTS.length;
        byte[] material = new byte[data.length + 1];
        System.arraycopy(data, 0, material, 1, data.length);
        int[] words = littleEndianWords(material);

        int totalTerms = coefficientCount + words.length;
        int[] terms = new int[totalTerms];
        int[] offsets = new int[totalTerms];
        for (int index = 0; index < coefficientCount; index++) {
            terms[index] = CS64_COEFFICIENTS[index][0] * factors[index % 2];
            offsets[index] = CS64_COEFFICIENTS[index][1];
        }
        for (int index = 0; index < words.length; index++) {
            int word = words[index];
            int term = word * (index % 2 == 0 ? keyA : keyB);
            int offset = rotate16(word) * (index % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms[coefficientCount + index] = term;
            offsets[coefficientCount + index] = offset;
        }

        int swapAcc = 0;
        int swapLast = 0;
        int state = 0;
        int acc = 0;
        for (int index = 0; index < totalTerms; index += 2) {
            int x = terms[index];
            int y = terms[index + 1];
            int t = rotate16(offsets[index] + x);
            int q = t * 0x76F50F34;
            swapAcc += rotate16(q) * 0xC3EFEA97 + q;
            t = rotate16(offsets[index + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rotate16(q) * 0x35BD1EC9 + q;
            swapAcc = swapAcc + swapLast;

            state = state * keyA + x;
            state = rotate16(state) * 0xCF98B111;
            state = rotate16(state) * 0x87085B9F;
            state = rotate16(state) * 0x12CEB96D;
            state = rotate16(state) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = rotate16(t) * 0xA27416F5;
            t = rotate16(t) * 0xD38396FF;
            state = rotate16(rotate16(t) * 0x7C932B89) * 0xBFA49F69;
            acc = acc + state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static int[] littleEndianWords(byte[] data) {
        int wordCount = (data.length + 3) / 4;
        int padded = wordCount * 4;
        int[] words = new int[wordCount % 2 == 0 ? wordCount : wordCount + 1];
        for (int index = 0; index < wordCount; index++) {
            int base = index * 4;
            int b0 = base < padded && base < data.length ? data[base] & 0xFF : 0;
            int b1 = base + 1 < data.length ? data[base + 1] & 0xFF : 0;
            int b2 = base + 2 < data.length ? data[base + 2] & 0xFF : 0;
            int b3 = base + 3 < data.length ? data[base + 3] & 0xFF : 0;
            words[index] = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }
        return words;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int rotate16(int value) {
        return (value << 16) | (value >>> 16);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString().toUpperCase(java.util.Locale.ROOT);
    }
}