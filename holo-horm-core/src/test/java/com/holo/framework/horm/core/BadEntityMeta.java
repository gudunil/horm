package com.holo.framework.horm.core;

/**
 * Deliberately broken stand-in for an APT-generated {@code XxxMeta} class.
 *
 * <p>Listed in {@code META-INF/horm/entities.idx} so that
 * {@link EntityMetaRegistry} exercises its defensive branches:
 * <ul>
 *   <li>{@code NonExistentMeta} (also in the index) does not exist on the
 *       classpath, so {@code Class.forName} throws and the registry's
 *       catch-block is exercised.</li>
 *   <li>This class's {@link #entityMeta()} returns a non-{@code EntityMeta}
 *       value, so the {@code instanceof} check fails and the registry's
 *       "returned non-EntityMeta" branch is exercised.</li>
 * </ul>
 *
 * <p>Neither entry should prevent {@link IndexedEntityMeta} from being
 * registered successfully — the registry swallows per-entry failures.
 */
public final class BadEntityMeta {

    private BadEntityMeta() {
    }

    /**
     * Returns a value that is intentionally <em>not</em> an
     * {@link com.holo.framework.horm.meta.EntityMeta}, to drive the
     * registry's defensive {@code instanceof} branch.
     */
    public static Object entityMeta() {
        return "not an EntityMeta";
    }
}
