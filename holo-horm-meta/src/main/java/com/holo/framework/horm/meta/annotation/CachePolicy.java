package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the cache policy for a {@link Cached} entity.
 *
 * <p>Used as the value of {@link Cached#policy()} on an {@code @Entity} class.
 * The APT processor reads this annotation and emits a
 * {@link com.holo.framework.horm.meta.CachePolicy} instance into the generated
 * {@code XxxMeta} companion class.
 *
 * <p>TTL strings accept the standard {@link java.time.Duration} parsed forms
 * — ISO-8601 (e.g. {@code "PT30M"}) or the simplified forms
 * {@code "<n>s"}, {@code "<n>m"}, {@code "<n>h"}, {@code "<n>d"} (e.g.
 * {@code "30m"}, {@code "2h"}). The APT validator (R12) rejects unparseable
 * strings at compile time.
 *
 * <p>Example:
 * <pre>{@code
 * @Entity
 * @Cached(policy = @CachePolicy(ttl = "1h", maxEntries = 5000, writeStrategy = WriteStrategy.THROUGH))
 * public class User extends Model<User> { ... }
 * }</pre>
 *
 * @see Cached
 * @see CacheLevel
 * @see EvictionPolicy
 * @see WriteStrategy
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePolicy {

    /** Time-to-live for non-null entries. Default {@code "30m"}. */
    String ttl() default "30m";

    /** Eviction policy. Default {@link EvictionPolicy#LRU LRU}. */
    EvictionPolicy eviction() default EvictionPolicy.LRU;

    /** Maximum number of entries before eviction. Default {@code 10000}. */
    int maxEntries() default 10000;

    /** Write propagation strategy. Default {@link WriteStrategy#AROUND AROUND}. */
    WriteStrategy writeStrategy() default WriteStrategy.AROUND;

    /** Whether {@code null} values may be cached as sentinels. Default {@code true}. */
    boolean nullable() default true;

    /** TTL for cached null sentinels. Default {@code "1m"}. */
    String nullTtl() default "1m";
}
