package com.holo.framework.horm.cache.key;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256-based hashing helpers for query-result cache keys.
 *
 * <p>Two overloads are provided:
 *
 * <ul>
 *   <li>{@link #hash(String...)} — free-form concatenation of any string
 *       parts. Lowest commitment, suitable when the caller already has a
 *       canonical representation of the query shape.</li>
 *   <li>{@link #hash(String, String, long, long)} — explicit
 *       conditions / orders / offset / limit. Preferred when the caller can
 *       reconstruct these from its own SQL builder; gives a stable hash
 *       independent of the query implementation.</li>
 * </ul>
 *
 * <p>All variants return the first 16 hex characters of the SHA-256 digest
 * — enough entropy (64 bits) to make collisions across a single entity
 * type's query-result set vanishingly unlikely while keeping cache keys
 * short.
 *
 * <p>The hashing is deterministic across JVM instances (UTF-8 byte encoding,
 * standard SHA-256) so the same query shape yields the same key on every
 * node of a distributed cache.
 */
public final class QueryHash {

    /** Length of the truncated hex digest returned by every overload. */
    public static final int HASH_LENGTH = 16;

    /**
     * Per-thread SHA-256 {@link MessageDigest} instance. MessageDigest is
     * not thread-safe, so each thread gets its own instance. The instance
     * is {@code reset()} before each use to clear any prior state.
     */
    private static final ThreadLocal<MessageDigest> SHA256_HOLDER =
        ThreadLocal.withInitial(() -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 algorithm not available", e);
            }
        });

    private QueryHash() {
        throw new AssertionError("QueryHash is a utility class and must not be instantiated");
    }

    /**
     * Hash an arbitrary set of string parts by concatenating them (in order,
     * with no separator) and taking the first {@value #HASH_LENGTH} hex
     * characters of the SHA-256 digest.
     *
     * <p>Callers must ensure the parts they pass uniquely and deterministically
     * describe the query — including bind values, since two queries with the
     * same SQL but different bind values are different result sets. A common
     * pattern is {@code QueryHash.hash(sqlFragment, bindings.toString())}.
     *
     * @param parts string parts to concatenate and hash; never {@code null}
     * @return 16-character lowercase hex string
     * @throws IllegalArgumentException if {@code parts} is null or any
     *         element is null
     */
    public static String hash(String... parts) {
        if (parts == null) {
            throw new IllegalArgumentException("parts must not be null");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null) {
                throw new IllegalArgumentException("parts[" + i + "] must not be null");
            }
            sb.append(parts[i]);
        }
        return sha256Truncated(sb.toString(), HASH_LENGTH);
    }

    /**
     * Hash the canonical query dimensions explicitly. Preferred over
     * {@link #hash(Query)} when the caller can supply these from its own
     * SQL builder, because the result is stable across {@link Query}
     * implementations.
     *
     * <p>The four arguments are concatenated with a {@code '|'} separator so
     * that {@code ("a","b",0,10)} and {@code ("ab","",0,10)} produce
     * different hashes — important when conditions strings can themselves
     * be empty.
     *
     * @param conditions canonical WHERE clause representation (e.g.
     *        SQL fragment with bound values interpolated) — never
     *        {@code null}, pass {@code ""} for an empty predicate
     * @param orders canonical ORDER BY representation — never {@code null},
     *        pass {@code ""} when no ordering is applied
     * @param offset rows to skip; {@code 0} when no offset
     * @param limit max rows; {@code 0} means unlimited (matching the
     *        {@link Query#limit} contract where {@code limit >= 0})
     * @return 16-character lowercase hex string
     */
    public static String hash(String conditions, String orders, long offset, long limit) {
        if (conditions == null) {
            throw new IllegalArgumentException("conditions must not be null (use \"\" for empty)");
        }
        if (orders == null) {
            throw new IllegalArgumentException("orders must not be null (use \"\" for empty)");
        }
        String payload = conditions + "|" + orders + "|" + offset + "|" + limit;
        return sha256Truncated(payload, HASH_LENGTH);
    }

    // ===== Internal =====

    private static String sha256Truncated(String input, int truncateLength) {
        MessageDigest md = SHA256_HOLDER.get();
        md.reset();
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        String hex = bytesToHex(digest);
        return hex.substring(0, Math.min(truncateLength, hex.length()));
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
