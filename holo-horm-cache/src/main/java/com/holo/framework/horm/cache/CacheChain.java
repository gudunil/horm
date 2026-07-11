package com.holo.framework.horm.cache;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Composes one or more {@link Cache} tiers into an ordered lookup chain.
 *
 * <p>A {@code CacheChain} is itself a {@link Cache} (so callers can use a
 * single-tier chain as a drop-in replacement for a bare cache), but it
 * also exposes composition operations that mutate the tier list. The
 * canonical topology is L1 (local) → L2 (distributed) → L3 (remote store):
 * a {@code get} walks the chain in order, returns the first hit, and
 * back-fills every tier above the hit on the way back.
 *
 * <p><b>Back-fill contract.</b> On a hit at tier {@code n}, the chain
 * must populate tiers {@code 0 .. n-1} with the same value (subject to
 * each tier's {@link CachePolicy}). This is what makes the chain behave
 * as a coherent multi-level cache rather than a fallback ladder.
 *
 * <p><b>Invalidation.</b> {@link #invalidate(Object)} and the bulk
 * variants propagate to every tier in the chain, so that a stale entry
 * in L1 cannot resurrect after a deletion at L2. The propagation order
 * is L1 → L2 → L3 (closest first), so that a concurrent read cannot
 * observe the deleted value re-materialised from a higher tier.
 *
 * <p><b>Mutation semantics.</b> The {@code append}/{@code insertAfter}/
 * {@code remove} methods return a new {@code CacheChain} (or mutate in
 * place, at the implementation's discretion — see each implementation's
 * javadoc). The contract does not mandate either flavour; callers that
 * need immutability should defensively copy.
 */
public interface CacheChain extends Cache {

    /**
     * Returns the constituent tiers in lookup order (L1 first, L3 last
     * for the canonical topology).
     *
     * <p>The returned list is a snapshot: mutations to the chain after
     * this call are not reflected in the list. Implementations SHOULD
     * return an unmodifiable list.
     *
     * @return a non-null, possibly-empty list of tiers in lookup order
     */
    List<Cache> levels();

    /**
     * Appends {@code cache} as the new last tier of the chain.
     *
     * <p>Callers are responsible for ensuring the appended cache's
     * {@link Cache#level()} is consistent with its position (e.g. an
     * appended L3 should not precede an existing L2). The chain does
     * not enforce ordering by level, to allow non-canonical topologies
     * (e.g. an L1-only chain that later gains an L2).
     *
     * @param cache the tier to append; must not be {@code null}
     * @return the chain (this, for fluent composition, or a new instance
     *         for immutable implementations)
     */
    CacheChain append(Cache cache);

    /**
     * Inserts {@code cache} immediately after the first tier whose
     * {@link Cache#level()} equals {@code level}.
     *
     * <p>If no tier with the given level exists, the behaviour is
     * implementation-defined: some implementations throw, others append.
     * Callers should ensure the target level is present before relying
     * on positioning.
     *
     * @param level the level after which to insert; must not be {@code null}
     * @param cache the tier to insert; must not be {@code null}
     * @return the chain (this, for fluent composition, or a new instance
     *         for immutable implementations)
     */
    CacheChain insertAfter(CacheLevel level, Cache cache);

    /**
     * Removes the first tier whose {@link Cache#level()} equals {@code level}.
     *
     * <p>If no such tier exists, the call is a no-op (the chain is
     * returned unchanged). Removing a tier does not close it; the
     * caller remains responsible for invoking {@link Cache#close()} on
     * the removed tier to release its resources.
     *
     * @param level the level to remove; must not be {@code null}
     * @return the chain (this, for fluent composition, or a new instance
     *         for immutable implementations)
     */
    CacheChain remove(CacheLevel level);

    /**
     * Bulk read-through with a custom batch loader.
     *
     * <p>Behaves like {@link Cache#getAll(Set, TypeReference)}, but on
     * discovering that some keys are absent from every tier, the chain
     * invokes {@code batchLoader} once with the missing set and caches
     * the returned entries back through the chain (per the active
     * {@code policy}). This avoids the thundering-herd problem of
     * per-key loaders on a large missing set.
     *
     * <p>The batch loader must return an entry for every key in the
     * missing set; missing entries in the returned map are treated as
     * "key absent from the authoritative store" and, depending on
     * {@link CachePolicy#nullable()}, may be cached as null sentinels.
     *
     * @param keys        the keys to fetch; must not be {@code null}
     * @param type        the expected value type; must not be {@code null}
     * @param batchLoader invoked with the subset of {@code keys} that
     *                    were missing from every tier; must not be
     *                    {@code null} and must not return {@code null}
     * @param policy      the policy governing back-filled entries;
     *                    must not be {@code null}
     * @param <K>         key type
     * @param <V>         value type
     * @return a non-null map containing an entry for every key in
     *         {@code keys} that was either found in the chain or
     *         returned by the batch loader
     */
    <K, V> Map<K, V> getAll(Set<K> keys,
                            TypeReference<V> type,
                            Function<Set<K>, Map<K, V>> batchLoader,
                            CachePolicy policy);
}
