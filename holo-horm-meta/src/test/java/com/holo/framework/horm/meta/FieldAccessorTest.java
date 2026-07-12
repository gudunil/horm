package com.holo.framework.horm.meta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldAccessorTest {

    static class SampleEntity {
        private String name;
        private int age;

        SampleEntity(String name, int age) {
            this.name = name;
            this.age = age;
        }

        String getName() { return name; }
        void setName(String name) { this.name = name; }
        int getAge() { return age; }
        void setAge(int age) { this.age = age; }
    }

    @Test
    void ofCreatesReadWriteAccessor() {
        FieldAccessor<SampleEntity, String> accessor =
            FieldAccessor.of(SampleEntity::getName, SampleEntity::setName);

        SampleEntity entity = new SampleEntity("Alice", 25);
        assertThat(accessor.get(entity)).isEqualTo("Alice");

        accessor.set(entity, "Bob");
        assertThat(accessor.get(entity)).isEqualTo("Bob");
    }

    @Test
    void getInvokesGetter() {
        FieldAccessor<SampleEntity, Integer> accessor =
            FieldAccessor.of(SampleEntity::getAge, SampleEntity::setAge);

        SampleEntity entity = new SampleEntity("Alice", 30);
        assertThat(accessor.get(entity)).isEqualTo(30);
    }

    @Test
    void setInvokesSetter() {
        FieldAccessor<SampleEntity, Integer> accessor =
            FieldAccessor.of(SampleEntity::getAge, SampleEntity::setAge);

        SampleEntity entity = new SampleEntity("Alice", 30);
        accessor.set(entity, 42);
        assertThat(entity.getAge()).isEqualTo(42);
    }

    @Test
    void readOnlyCreatesReadOnlyAccessor() {
        FieldAccessor<SampleEntity, String> accessor =
            FieldAccessor.readOnly(SampleEntity::getName);

        SampleEntity entity = new SampleEntity("Alice", 25);
        assertThat(accessor.get(entity)).isEqualTo("Alice");
    }

    @Test
    void readOnlySetThrowsUnsupportedOperationException() {
        FieldAccessor<SampleEntity, String> accessor =
            FieldAccessor.readOnly(SampleEntity::getName);

        SampleEntity entity = new SampleEntity("Alice", 25);
        assertThatThrownBy(() -> accessor.set(entity, "Bob"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
