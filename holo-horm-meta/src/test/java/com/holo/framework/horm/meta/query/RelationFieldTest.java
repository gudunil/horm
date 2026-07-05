package com.holo.framework.horm.meta.query;

import com.holo.framework.horm.meta.RelationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RelationField}, focusing on the {@code static of(...)}
 * factory matrix across all five {@link RelationType} values and the
 * null-optionals handling.
 */
class RelationFieldTest {

    private static final Class<Owner> OWNER = RelationFieldTest.Owner.class;
    private static final Class<Target> TARGET = RelationFieldTest.Target.class;
    private static final Class<Through> THROUGH = RelationFieldTest.Through.class;

    private static final Map<RelationType, String> TYPE_TO_STRING = Map.of(
        RelationType.BELONGS_TO, "BELONGS_TO",
        RelationType.HAS_ONE, "HAS_ONE",
        RelationType.HAS_MANY, "HAS_MANY",
        RelationType.HAS_AND_BELONGS_TO_MANY, "HAS_AND_BELONGS_TO_MANY",
        RelationType.HAS_MANY_THROUGH, "HAS_MANY_THROUGH"
    );

    @Test
    void ofFactoryReturnsAllAttributes() {
        for (RelationType type : RelationType.values()) {
            String name = "rel_" + TYPE_TO_STRING.get(type).toLowerCase();
            String fk = "fk_" + TYPE_TO_STRING.get(type);
            String afk = "afk_" + TYPE_TO_STRING.get(type);
            String jt = (type == RelationType.HAS_AND_BELONGS_TO_MANY)
                ? "join_table_" + TYPE_TO_STRING.get(type) : null;
            Class<?> through = (type == RelationType.HAS_MANY_THROUGH) ? THROUGH : null;

            RelationField<Owner, Target> field = RelationField.of(
                OWNER, TARGET, name, type, fk, afk, jt, through);

            assertThat(field.entityType()).isEqualTo(OWNER);
            assertThat(field.targetType()).isEqualTo(TARGET);
            assertThat(field.name()).isEqualTo(name);
            assertThat(field.relationType()).isEqualTo(type);
            assertThat(field.foreignKey()).isEqualTo(fk);
            assertThat(field.associationForeignKey()).isEqualTo(afk);
            assertThat(field.joinTable()).isEqualTo(jt);
            assertThat(field.through()).isEqualTo(through);
        }
    }

    @Test
    void ofFactoryWithNullOptionals() {
        RelationField<Owner, Target> field = RelationField.of(
            OWNER, TARGET, "sparse", RelationType.HAS_MANY,
            null, null, null, null);

        assertThat(field.foreignKey()).isNull();
        assertThat(field.associationForeignKey()).isNull();
        assertThat(field.joinTable()).isNull();
        assertThat(field.through()).isNull();
        assertThat(field.relationType()).isEqualTo(RelationType.HAS_MANY);
    }

    @Test
    void toStringContainsRelationInfo() {
        RelationField<Owner, Target> field = RelationField.of(
            OWNER, TARGET, "orders", RelationType.HAS_MANY,
            "user_id", null, null, null);

        String s = field.toString();
        assertThat(s).contains("Owner");
        assertThat(s).contains("orders");
        assertThat(s).contains("Target");
        assertThat(s).contains("HAS_MANY");
    }

    static final class Owner { }
    static final class Target { }
    static final class Through { }
}
