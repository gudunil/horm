package com.holo.framework.horm.core.cache;

import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.cache.CaffeineCache;
import com.holo.framework.horm.cache.DefaultCacheChain;
import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.Repository;
import com.holo.framework.horm.core.TransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 integration tests for {@code findMany} batch loading through the cache chain.
 *
 * <p>Uses a real {@link CaffeineCache} L1 tier wired into a {@link DefaultCacheChain}
 * so that hit/miss/back-fill behaviour can be asserted via the cache's
 * {@code estimatedSize} and object identity.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindManyCacheIntegrationTest {

    private static final String H2_URL = "jdbc:h2:mem:horm;MODE=MySQL;DB_CLOSE_DELAY=-1";

    /** Direct connection for DDL, data seeding and verification. */
    private Connection conn;

    /** The cache chain installed on the {@link HormContext}. */
    private DefaultCacheChain chain;

    @BeforeAll
    void setup() throws SQLException {
        EntityMetaRegistry.reload();

        conn = DriverManager.getConnection(H2_URL);
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS cached_users (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  name VARCHAR(128) NOT NULL" +
                ")");
        }

        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(30))
            .writeStrategy(com.holo.framework.horm.cache.WriteStrategy.THROUGH)
            .maxEntries(1000)
            .build();
        CaffeineCache l1 = CaffeineCache.create("cached-users-l1", policy);
        chain = new DefaultCacheChain(l1);

        Horm.install(new HormContext((DataSourceProvider) () -> {
            Connection c = DriverManager.getConnection(H2_URL);
            c.setAutoCommit(true);
            return c;
        }, chain));
    }

    @AfterAll
    void teardown() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE cached_users");
        }
        conn.close();
        chain.close();
    }

    @AfterEach
    void cleanup() throws SQLException {
        TransactionManager.clear();
        chain.invalidateAll();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM cached_users");
        }
    }

    // ===== Helpers =====

    private CachedUser newUser(String name) {
        CachedUser u = new CachedUser();
        u.setName(name);
        return u;
    }

    private long cacheSize() {
        return chain.stats().estimatedSize();
    }

    // ===== Tests =====

    /** All ids missed the cache: the batch loader should fetch every row from H2. */
    @Test
    void findManyAllMissLoadsEveryRowFromDatabase() {
        CachedUser alice = Horm.tx(() -> {
            CachedUser a = newUser("alice");
            a.save();
            return a;
        });
        CachedUser bob = Horm.tx(() -> {
            CachedUser b = newUser("bob");
            b.save();
            return b;
        });

        // THROUGH writes populate the cache on commit; clear it to force misses.
        chain.invalidateAll();
        assertThat(cacheSize()).isZero();

        Map<Object, CachedUser> found = Model.findMany(CachedUser.class,
            List.of(alice.getId(), bob.getId()));

        assertThat(found).containsOnlyKeys(alice.getId(), bob.getId());
        assertThat(found.get(alice.getId()).getName()).isEqualTo("alice");
        assertThat(found.get(bob.getId()).getName()).isEqualTo("bob");
        assertThat(cacheSize()).isEqualTo(2L);
    }

    /** One id is already cached; only the missing id should hit the database. */
    @Test
    void findManyPartialHitLoadsOnlyMissingKeys() {
        CachedUser carol = Horm.tx(() -> {
            CachedUser c = newUser("carol");
            c.save();
            return c;
        });
        CachedUser dave = Horm.tx(() -> {
            CachedUser d = newUser("dave");
            d.save();
            return d;
        });

        // THROUGH writes populate the cache on commit; clear it first.
        chain.invalidateAll();

        // Warm the cache for carol only.
        CachedUser cachedCarol = Model.find(CachedUser.class, carol.getId());
        assertThat(cachedCarol).isNotNull();
        assertThat(cacheSize()).isEqualTo(1L);

        Map<Object, CachedUser> found = Model.findMany(CachedUser.class,
            List.of(carol.getId(), dave.getId()));

        assertThat(found).containsOnlyKeys(carol.getId(), dave.getId());
        assertThat(found.get(carol.getId())).isSameAs(cachedCarol);
        assertThat(found.get(dave.getId()).getName()).isEqualTo("dave");
        assertThat(cacheSize()).isEqualTo(2L);
    }

    /** After a findMany, every returned id is back-filled so a second lookup is all hits. */
    @Test
    void findManyBackFillsCacheForSubsequentLookups() {
        CachedUser eve = Horm.tx(() -> {
            CachedUser e = newUser("eve");
            e.save();
            return e;
        });
        CachedUser frank = Horm.tx(() -> {
            CachedUser f = newUser("frank");
            f.save();
            return f;
        });

        Map<Object, CachedUser> first = Model.findMany(CachedUser.class,
            List.of(eve.getId(), frank.getId()));
        assertThat(cacheSize()).isEqualTo(2L);

        Map<Object, CachedUser> second = Model.findMany(CachedUser.class,
            List.of(eve.getId(), frank.getId()));

        assertThat(second.get(eve.getId())).isSameAs(first.get(eve.getId()));
        assertThat(second.get(frank.getId())).isSameAs(first.get(frank.getId()));
    }

    /** The Repository.findMany entry point routes through the cache chain as well. */
    @Test
    void repositoryFindManyReturnsCachedAndLoadedEntries() {
        CachedUser grace = Horm.tx(() -> {
            CachedUser g = newUser("grace");
            g.save();
            return g;
        });
        CachedUser henry = Horm.tx(() -> {
            CachedUser h = newUser("henry");
            h.save();
            return h;
        });

        Repository<CachedUser> repo = Horm.repository(CachedUser.class);
        Map<Object, CachedUser> found = repo.findMany(List.of(grace.getId(), henry.getId()));

        assertThat(found).containsOnlyKeys(grace.getId(), henry.getId());
        assertThat(found.get(grace.getId()).getName()).isEqualTo("grace");
        assertThat(found.get(henry.getId()).getName()).isEqualTo("henry");
        assertThat(cacheSize()).isEqualTo(2L);
    }

    /** Absent ids are omitted from the result rather than causing failures. */
    @Test
    void findManyOmitsAbsentIds() {
        CachedUser ivy = Horm.tx(() -> {
            CachedUser i = newUser("ivy");
            i.save();
            return i;
        });

        Map<Object, CachedUser> found = Model.findMany(CachedUser.class,
            List.of(ivy.getId(), 9999L));

        assertThat(found).containsOnlyKeys(ivy.getId());
        assertThat(found.get(ivy.getId()).getName()).isEqualTo("ivy");
    }
}
