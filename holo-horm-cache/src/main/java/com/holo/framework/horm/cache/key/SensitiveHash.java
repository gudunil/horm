package com.holo.framework.horm.cache.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256-based hashing for sensitive values (PII, credentials) that must
 * not appear in clear text in cache keys.
 *
 * <p>Unlike {@link QueryHash}, which truncates to 16 hex characters, the
 * default {@link #hash(Object)} returns the full 64-character SHA-256
 * digest. Sensitive values often share low-entropy prefixes (country codes,
 * common email domains) where a 16-character truncation would meaningfully
 * raise the collision probability; the full digest keeps that risk
 * negligible while still being a one-way function.
 *
 * <p>The hash is non-reversible: SHA-256 has no known practical preimage
 * attack, so storing {@code SensitiveHash.hash(ssn)} in a cache key does
 * not leak the original SSN even if the cache is dumped. Callers that need
 * to look up by a sensitive value re-hash the candidate input and compare
 * digests.
 *
 * <p>Salted hashing is intentionally <em>not</em> provided here. Cache keys
 * must be deterministic across nodes and across restarts (otherwise the
 * cache hit rate collapses); a per-installation salt would defeat that.
 * Callers with stricter threat models should hash at the application layer
 * before passing the value to the cache.
 */
public final class SensitiveHash {

    /** Length of a full SHA-256 hex digest (32 bytes * 2 hex chars). */
    public static final int FULL_HASH_LENGTH = 64;

    private SensitiveHash() {
        throw new AssertionError("SensitiveHash is a utility class and must not be instantiated");
    }

    /**
     * Hash {@code value.toString()} (UTF-8) and return the full 64-character
     * lowercase hex SHA-256 digest.
     *
     * @param value the sensitive value; never {@code null}
     * @return 64-character lowercase hex string
     * @throws IllegalArgumentException if {@code value} is null
     */
    public static String hash(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return sha256Hex(value.toString());
    }

    /**
     * Hash {@code value.toString()} (UTF-8) and return the first
     * {@code truncateLength} hex characters of the SHA-256 digest.
     *
     * <p>{@code truncateLength} must be in {@code [1, 64]}. Truncating
     * below 16 characters is discouraged for sensitive values — it
     * significantly raises the collision probability for low-entropy
     * inputs. The bounds check exists to catch off-by-one mistakes, not to
     * endorse aggressive truncation.
     *
     * @param value the sensitive value; never {@code null}
     * @param truncateLength desired hex length, in {@code [1, 64]}
     * @return truncated lowercase hex string
     * @throws IllegalArgumentException if {@code value} is null or
     *         {@code truncateLength} is out of bounds
     */
    public static String hash(Object value, int truncateLength) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (truncateLength < 1 || truncateLength > FULL_HASH_LENGTH) {
            throw new IllegalArgumentException(
                "truncateLength must be in [1, " + FULL_HASH_LENGTH + "], got " + truncateLength);
        }
        String full = sha256Hex(value.toString());
        return full.substring(0, truncateLength);
    }

    // ===== Internal =====

    private static String sha256Hex(String input) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec and shipped with every JDK.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX_DIGITS[(b >> 4) & 0x0F]);
            sb.append(HEX_DIGITS[b & 0x0F]);
        }
        return sb.toString();
    }

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
}
