package com.holo.framework.horm.core;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Horm}.
 *
 * <p>Validates that {@link Horm#install(HormContext)} / {@link Horm#context()}
 * delegate to {@link HormContext}, and that {@link Horm#repository(Class)}
 * returns a {@link JdbcRepository} instance once a context is installed and
 * the entity type is registered (M1-7 behaviour; M1-6 used to throw
 * {@link UnsupportedOperationException} here).
 */
class HormTest {

    @AfterEach
    void resetContext() {
        HormContext.install(null);
        EntityMetaRegistry.clear();
    }

    @Test
    void installDelegatesToHormContext() {
        HormContext ctx = new HormContext(mock(Connection.class));
        Horm.install(ctx);
        assertThat(HormContext.current()).isSameAs(ctx);
    }

    @Test
    void contextReturnsInstalledContext() {
        HormContext ctx = new HormContext(mock(Connection.class));
        Horm.install(ctx);
        assertThat(Horm.context()).isSameAs(ctx);
    }

    @Test
    void contextThrowsWhenNotInstalled() {
        HormContext.install(null);
        assertThatThrownBy(Horm::context)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not installed");
    }

    /**
     * M1-7 replaces the {@code UnsupportedOperationException} stub with a
     * real {@link JdbcRepository} bound to the installed context. Verifying
     * the concrete subtype (not just {@code Repository.class}) keeps the
     * test honest about what {@link Horm} actually wires up.
     */
    @Test
    void repositoryReturnsJdbcRepositoryWhenContextInstalled() {
        HormContext ctx = new HormContext(mock(Connection.class));
        Horm.install(ctx);
        registerTestEntityMeta();

        Repository<HormTestEntity> repo = Horm.repository(HormTestEntity.class);
        assertThat(repo).isInstanceOf(JdbcRepository.class);
    }

    /**
     * Builds a minimal {@link EntityMeta} for {@link HormTestEntity} with a
     * mocked {@link Mapper} so that {@link JdbcRepository}'s constructor
     * (which calls {@link EntityMetaRegistry#lookup}) succeeds.
     */
    @SuppressWarnings("unchecked")
    private static void registerTestEntityMeta() {
        Mapper<HormTestEntity> mapper = mock(Mapper.class);
        FieldMeta<Long> idField = FieldMeta.<Long>builder()
            .name("id").column("id").type(Long.class).id(true)
            .build();
        EntityMeta<HormTestEntity> meta = EntityMeta.<HormTestEntity>builder()
            .type(HormTestEntity.class)
            .tableName("horm_test_entities")
            .fields(List.of(idField))
            .idField(idField)
            .mapper(mapper)
            .build();
        EntityMetaRegistry.registerManual(meta);
    }

    /** Concrete CRTP subtype used only as a registry key for {@link Horm}. */
    static final class HormTestEntity extends Model<HormTestEntity> {
    }
}
