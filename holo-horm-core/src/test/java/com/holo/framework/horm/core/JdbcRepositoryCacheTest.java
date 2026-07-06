package com.holo.framework.horm.core;

import com.holo.framework.horm.cache.CacheChain;
import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.cache.TypeReference;
import com.holo.framework.horm.cache.key.CacheKey;
import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.holo.framework.horm.meta.annotation.CacheLevel;
import com.holo.framework.horm.meta.annotation.WriteStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JdbcRepository} cache integration.
 *
 * <p>Uses a hand-built {@link EntityMeta} with {@code cached=true} and a
 * Mockito mock {@link CacheChain} to verify cache hit/miss paths and
 * post-commit invalidation/population behaviour.
 */
class JdbcRepositoryCacheTest {

    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    @SuppressWarnings("unchecked")
    private Mapper<TestEntity> mapper;
    private CacheChain cacheChain;
    private JdbcRepository<TestEntity> repo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conn = mock(Connection.class);
        ps = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        mapper = mock(Mapper.class);
        cacheChain = mock(CacheChain.class);

        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .build();
        FieldMeta<String> emailField = FieldMeta.<String>builder()
            .name("email").column("email").type(String.class)
            .build();
        com.holo.framework.horm.meta.CachePolicy metaPolicy =
            com.holo.framework.horm.meta.CachePolicy.builder()
                .writeStrategy(WriteStrategy.AROUND)
                .build();
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(idField, emailField))
            .idField(idField)
            .mapper(mapper)
            .cached(true)
            .cachePolicy(metaPolicy)
            .cacheLevels(new CacheLevel[]{CacheLevel.L1})
            .build();
        EntityMetaRegistry.registerManual(meta);

        repo = new JdbcRepository<>(TestEntity.class, new HormContext(conn, cacheChain));
    }

    @AfterEach
    void tearDown() {
        EntityMetaRegistry.clear();
        TransactionManager.clear();
    }

    @Test
    void findReturnsCachedValueWithoutHittingDatabase() throws Exception {
        TestEntity cached = entity(1L, "cached@holo.dev");
        CacheKey key = key(1L);

        when(cacheChain.get(eq(key), any(TypeReference.class), any(Supplier.class), any(CachePolicy.class)))
            .thenReturn(Optional.of(cached));

        TestEntity result = repo.find(1L);

        assertThat(result).isSameAs(cached);
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findLoadsFromDatabaseOnCacheMissAndReturnsLoadedValue() throws Exception {
        TestEntity loaded = entity(2L, "loaded@holo.dev");
        CacheKey key = key(2L);

        when(cacheChain.get(eq(key), any(TypeReference.class), any(Supplier.class), any(CachePolicy.class)))
            .thenAnswer(invocation -> {
                Supplier<TestEntity> loader = invocation.getArgument(2);
                return Optional.ofNullable(loader.get());
            });
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(mapper.map(any(Row.class))).thenReturn(loaded);

        TestEntity result = repo.find(2L);

        assertThat(result).isSameAs(loaded);
        verify(conn).prepareStatement("SELECT * FROM test_entities WHERE id = ?");
    }

    @Test
    void findBypassesCacheWhenEntityNotCached() throws Exception {
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(
                FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build(),
                FieldMeta.<String>builder().name("email").column("email").type(String.class).build()))
            .idField(FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build())
            .mapper(mapper)
            .cached(false)
            .build();
        EntityMetaRegistry.registerManual(meta);
        JdbcRepository<TestEntity> uncachedRepo =
            new JdbcRepository<>(TestEntity.class, new HormContext(conn, cacheChain));

        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("boom"));

        // We do not stub cacheChain; if it were called, default Mockito return
        // would yield null/empty and break the test.
        assertThatThrownBy(() -> uncachedRepo.find(1L))
            .isInstanceOf(HormException.class)
            .hasMessageContaining("Failed to find");
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertSchedulesCachePutForThroughStrategy() throws Exception {
        setWriteStrategy(WriteStrategy.THROUGH);
        TestEntity entity = new TestEntity();
        when(mapper.getId(entity)).thenReturn(null);
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet genKeys = mock(ResultSet.class);
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true);
        when(genKeys.getLong(1)).thenReturn(7L);
        when(mapper.getId(entity)).thenReturn(null).thenReturn(7L);

        TransactionManager.execute(ctx(), () -> repo.save(entity));

        ArgumentCaptor<CacheKey> keyCaptor = ArgumentCaptor.forClass(CacheKey.class);
        ArgumentCaptor<TestEntity> valueCaptor = ArgumentCaptor.forClass(TestEntity.class);
        verify(cacheChain).put(keyCaptor.capture(), valueCaptor.capture(), any(CachePolicy.class));
        assertThat(keyCaptor.getValue().keyValue()).isEqualTo("7");
        assertThat(valueCaptor.getValue()).isSameAs(entity);
    }

    @Test
    void insertDoesNotScheduleCachePutForAroundStrategy() throws Exception {
        TestEntity entity = new TestEntity();
        when(mapper.getId(entity)).thenReturn(null);
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);
        ResultSet genKeys = mock(ResultSet.class);
        when(ps.getGeneratedKeys()).thenReturn(genKeys);
        when(genKeys.next()).thenReturn(true);
        when(genKeys.getLong(1)).thenReturn(7L);

        TransactionManager.execute(ctx(), () -> repo.save(entity));

        verify(cacheChain, never()).put(any(CacheKey.class), any(), any(CachePolicy.class));
    }

    @Test
    void updateSchedulesInvalidateForAroundStrategy() throws Exception {
        TestEntity entity = entity(3L, "update@holo.dev");
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TransactionManager.execute(ctx(), () -> repo.save(entity));

        ArgumentCaptor<CacheKey> keyCaptor = ArgumentCaptor.forClass(CacheKey.class);
        verify(cacheChain).invalidate(keyCaptor.capture());
        assertThat(keyCaptor.getValue().keyValue()).isEqualTo("3");
        verify(cacheChain, never()).put(any(CacheKey.class), any(), any(CachePolicy.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateSchedulesPutForThroughStrategy() throws Exception {
        setWriteStrategy(WriteStrategy.THROUGH);
        TestEntity entity = entity(4L, "through@holo.dev");
        when(mapper.toRow(entity)).thenReturn(Row.create("test_entities"));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TransactionManager.execute(ctx(), () -> repo.save(entity));

        ArgumentCaptor<CacheKey> keyCaptor = ArgumentCaptor.forClass(CacheKey.class);
        ArgumentCaptor<TestEntity> valueCaptor = ArgumentCaptor.forClass(TestEntity.class);
        verify(cacheChain).put(keyCaptor.capture(), valueCaptor.capture(), any(CachePolicy.class));
        assertThat(keyCaptor.getValue().keyValue()).isEqualTo("4");
        assertThat(valueCaptor.getValue()).isSameAs(entity);
        verify(cacheChain, never()).invalidate(any(CacheKey.class));
    }

    @Test
    void deleteSchedulesInvalidate() throws Exception {
        TestEntity entity = entity(5L, "delete@holo.dev");
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TransactionManager.execute(ctx(), () -> repo.delete(entity));

        ArgumentCaptor<CacheKey> keyCaptor = ArgumentCaptor.forClass(CacheKey.class);
        verify(cacheChain).invalidate(keyCaptor.capture());
        assertThat(keyCaptor.getValue().keyValue()).isEqualTo("5");
    }

    @Test
    void deleteByIdSchedulesInvalidate() throws Exception {
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenReturn(1);

        TransactionManager.execute(ctx(), () -> repo.deleteById(6L));

        ArgumentCaptor<CacheKey> keyCaptor = ArgumentCaptor.forClass(CacheKey.class);
        verify(cacheChain).invalidate(keyCaptor.capture());
        assertThat(keyCaptor.getValue().keyValue()).isEqualTo("6");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findManyReturnsFullyCachedResultWithoutDatabase() throws Exception {
        TestEntity one = entity(10L, "one@holo.dev");
        TestEntity two = entity(11L, "two@holo.dev");

        when(cacheChain.getAll(any(Set.class), any(TypeReference.class), any(Function.class), any(CachePolicy.class)))
            .thenReturn(Map.of(key(10L), one, key(11L), two));

        Map<Object, TestEntity> result = repo.findMany(List.of(10L, 11L));

        assertThat(result).containsOnlyKeys(10L, 11L);
        assertThat(result.get(10L)).isSameAs(one);
        assertThat(result.get(11L)).isSameAs(two);
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void findManyLoadsMissingKeysFromDatabase() throws Exception {
        TestEntity one = entity(20L, "one@holo.dev");
        TestEntity two = entity(21L, "two@holo.dev");
        CacheKey keyOne = key(20L);
        CacheKey keyTwo = key(21L);

        when(cacheChain.getAll(any(Set.class), any(TypeReference.class), any(Function.class), any(CachePolicy.class)))
            .thenAnswer(invocation -> {
                Set<CacheKey> requested = invocation.getArgument(0);
                Function<Set<CacheKey>, Map<CacheKey, TestEntity>> loader = invocation.getArgument(2);
                Map<CacheKey, TestEntity> loaded = loader.apply(requested);
                return loaded == null ? Map.of() : loaded;
            });
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(mapper.map(any(Row.class))).thenReturn(one).thenReturn(two);

        Map<Object, TestEntity> result = repo.findMany(List.of(20L, 21L));

        assertThat(result).containsOnlyKeys(20L, 21L);
        assertThat(result.get(20L)).isSameAs(one);
        assertThat(result.get(21L)).isSameAs(two);
        verify(ps, times(2)).setObject(any(Integer.class), any());
    }

    @Test
    void findManyBypassesCacheWhenEntityNotCached() throws Exception {
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(
                FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build(),
                FieldMeta.<String>builder().name("email").column("email").type(String.class).build()))
            .idField(FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build())
            .mapper(mapper)
            .cached(false)
            .build();
        EntityMetaRegistry.registerManual(meta);
        JdbcRepository<TestEntity> uncachedRepo =
            new JdbcRepository<>(TestEntity.class, new HormContext(conn, cacheChain));

        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Map<Object, TestEntity> result = uncachedRepo.findMany(List.of(1L));

        assertThat(result).isEmpty();
        verify(cacheChain, never()).getAll(any(), any(), any(), any());
    }

    private HormContext ctx() {
        return new HormContext(conn, cacheChain);
    }

    private void setWriteStrategy(WriteStrategy strategy) {
        EntityMeta<TestEntity> meta = EntityMeta.<TestEntity>builder()
            .type(TestEntity.class)
            .tableName("test_entities")
            .fields(List.of(
                FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build(),
                FieldMeta.<String>builder().name("email").column("email").type(String.class).build()))
            .idField(FieldMeta.<Long>builder().name("id").column("id").type(Long.class).id(true).build())
            .mapper(mapper)
            .cached(true)
            .cachePolicy(com.holo.framework.horm.meta.CachePolicy.builder()
                .writeStrategy(strategy)
                .build())
            .cacheLevels(new CacheLevel[]{CacheLevel.L1})
            .build();
        EntityMetaRegistry.registerManual(meta);
        repo = new JdbcRepository<>(TestEntity.class, new HormContext(conn, cacheChain));
    }

    private TestEntity entity(Long id, String email) {
        TestEntity e = new TestEntity();
        when(mapper.getId(e)).thenReturn(id);
        e.setId(id);
        e.setEmail(email);
        return e;
    }

    private CacheKey key(Object id) {
        return new com.holo.framework.horm.cache.key.CacheKeyBuilder()
            .entityType(TestEntity.class)
            .idKey(id)
            .build();
    }

    /** Concrete CRTP subtype used only as a registry key for these tests. */
    static final class TestEntity extends Model<TestEntity> {
        private Long id;
        private String email;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
