package com.holo.framework.horm.cache;

/**
 * Write strategy governing how {@link Cache#put} propagates to the
 * authoritative data source.
 *
 * <p>Declared on a {@link CachePolicy} and interpreted by the cache writer
 * (e.g. a {@link CacheWriter} callback wired into the chain). The strategies
 * map to the standard cache write patterns:
 *
 * <ul>
 *   <li>{@link #THROUGH} — Write-Through. The cache synchronously writes to
 *       the backing store before acknowledging the caller. Strong consistency
 *       at the cost of write latency.</li>
 *   <li>{@link #BEHIND} — Write-Behind. The cache acknowledges the write
 *       immediately and persists to the store asynchronously. Lowest write
 *       latency, but exposes a data-loss window on failure.</li>
 *   <li>{@link #AROUND} — Write-Around. The cache is bypassed on write (data
 *       goes straight to the store) and is only populated on subsequent
 *       reads. Prevents cache pollution from write-once-read-never workloads.</li>
 * </ul>
 */
public enum WriteStrategy {

    THROUGH,
    BEHIND,
    AROUND
}
