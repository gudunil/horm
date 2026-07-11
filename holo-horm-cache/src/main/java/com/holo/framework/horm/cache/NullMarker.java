package com.holo.framework.horm.cache;

/**
 * Sentinel value stored by a {@link Cache} to represent a cached
 * {@code null} result, enabling negative caching without ambiguity.
 *
 * <p>When {@link CachePolicy#nullable()} is {@code true} and the
 * configured loader returns {@code null} (signalling "the authoritative
 * store has no entry for this key"), the cache layer needs a way to
 * distinguish <em>"key absent from cache"</em> (must invoke the loader)
 * from <em>"loader already returned null, do not call it again"</em>.
 * Storing {@code null} directly cannot make this distinction, because a
 * cache entry whose value is {@code null} is indistinguishable from no
 * entry at all under the standard {@link java.util.Map} semantics that
 * most cache providers follow.
 *
 * <p>The {@code NullMarker} solves this by substituting a non-null
 * sentinel for the absent value at write time:
 *
 * <pre>{@code
 * V loaded = loader.get();
 * if (loaded == null) {
 *     cache.put(key, NullMarker.instance(), policy);
 * } else {
 *     cache.put(key, loaded, policy);
 * }
 * }</pre>
 *
 * <p>On read, a cache tier that returns the marker is treated as a hit
 * whose logical value is {@code null}; the chain short-circuits the
 * loader and returns {@link java.util.Optional#empty()}:
 *
 * <pre>{@code
 * Optional<V> cached = cache.get(key, type);
 * if (cached.isPresent() && NullMarker.isNullMarker(cached.get())) {
 *     return Optional.empty();   // negative cache hit
 * }
 * }</pre>
 *
 * <p><b>Singleton.</b> {@code NullMarker} is stateless and therefore
 * safe to share across threads, modules and caches. Use
 * {@link #instance()} to obtain the canonical singleton. The class is
 * {@code final} with a private constructor to enforce the singleton
 * invariant — there is no reason for a caller to hold more than one
 * marker since the instance carries no per-call state.
 *
 * <p><b>Identity vs. equality.</b> Because the singleton is the only
 * instance, callers may use either reference equality ({@code ==}) or
 * {@link #equals(Object)} interchangeably; the latter delegates to
 * {@link Object#equals(Object)} and thus reduces to identity for this
 * class. {@link #isNullMarker(Object)} uses {@code instanceof} so that
 * it remains correct even if a future refactoring introduces
 * subclasses (defensive against accidental serialisation round-trips
 * that may create new instances).
 *
 * <p><b>Serialisation.</b> {@code NullMarker} does not implement
 * {@link java.io.Serializable}. Cache providers that round-trip values
 * through a serialiser (e.g. Redis with JSON encoding) MUST handle the
 * marker explicitly at the (de)serialisation boundary — typically by
 * substituting a documented JSON literal such as {@code {"__null__":true}}
 * — because deserialisation that bypasses {@link #instance()} would
 * break the singleton invariant. The contract deliberately leaves this
 * concern to the provider: the marker's role is an in-process
 * coalescing signal, not a wire format.
 */
public final class NullMarker {

    /** Canonical singleton instance. */
    private static final NullMarker INSTANCE = new NullMarker();

    /**
     * Returns the canonical {@code NullMarker} singleton. The same
     * instance is returned on every call; callers MUST NOT assume
     * ownership and SHOULD NOT mutate any state (the marker is stateless
     * anyway).
     *
     * @return the singleton {@code NullMarker} instance; never {@code null}
     */
    public static NullMarker instance() {
        return INSTANCE;
    }

    /**
     * Tests whether the given value is a {@code NullMarker} reference.
     *
     * <p>This is the recommended way to detect a cached null sentinel on
     * read: it is null-safe (returns {@code false} for a {@code null}
     * argument) and uses {@code instanceof} so it remains correct if the
     * value was loaded via a deserialisation path that may have created
     * a non-singleton instance.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} if {@code value} is a {@code NullMarker}
     *         instance; {@code false} otherwise (including for
     *         {@code null} arguments)
     */
    public static boolean isNullMarker(Object value) {
        return value instanceof NullMarker;
    }

    /**
     * Private constructor; the only sanctioned creation path is
     * {@link #instance()}. Kept private to enforce the singleton
     * invariant — there is no reason for a caller to construct a
     * {@code NullMarker} directly since the instance is stateless and
     * interchangeable.
     */
    private NullMarker() {
        // no state to initialise
    }

    /**
     * Returns a short, diagnostic string for log output. The format is
     * deliberately stable so that log-scraping tooling can recognise it.
     *
     * @return the string {@code "NullMarker"}
     */
    @Override
    public String toString() {
        return "NullMarker";
    }
}
