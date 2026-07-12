package com.holo.framework.horm.meta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code @Entity} class as eligible for caching.
 *
 * <p>At compile time the APT processor reads this annotation and emits
 * static constants into the generated {@code XxxMeta} companion class:
 * <ul>
 *   <li>{@code CACHED} — boolean, whether caching is enabled</li>
 *   <li>{@code CACHE_POLICY} — {@link com.holo.framework.horm.meta.CachePolicy}
 *       instance built from {@link #policy()}</li>
 *   <li>{@code CACHE_LEVELS} — {@code CacheLevel[]} array from {@link #levels()}</li>
 * </ul>
 *
 * <p>When {@link #enabled()} is {@code false}, the entity is treated as if
 * unannotated — the generated {@code XxxMeta.entityMeta()} factory returns
 * {@code cached()=false} and {@code cachePolicy()=null}. This allows toggling
 * caching without removing the annotation.
 *
 * <p>Example:
 * <pre>{@code
 * @Entity
 * @Cached(levels = {CacheLevel.L1, CacheLevel.L2}, policy = @CachePolicy(ttl = "1h"))
 * public class User extends Model<User> { ... }
 * }</pre>
 *
 * @see CachePolicy
 * @see CacheLevel
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Cached {

    /**
     * Cache levels to participate for this entity. Default {@code {L1}}.
     * <p>The array must not contain {@code null} (enforced by R12).
     */
    CacheLevel[] levels() default {CacheLevel.L1};

    /** Cache policy. Defaults to a {@code @CachePolicy} with all defaults. */
    CachePolicy policy() default @CachePolicy;

    /** Whether caching is enabled. Default {@code true}. When {@code false}, all cache logic is short-circuited. */
    boolean enabled() default true;
}
