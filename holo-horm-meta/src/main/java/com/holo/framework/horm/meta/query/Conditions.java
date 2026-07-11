package com.holo.framework.horm.meta.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factory for leaf {@link Condition} instances. Column names are sourced from
 * the supplied {@link TypedField} (APT-frozen at compile time), so only the
 * value bindings flow through {@code ?} placeholders.
 */
public final class Conditions {

    private Conditions() {
    }

    public static <T> Condition eq(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " = ?", Arrays.asList(value));
    }

    public static <T> Condition ne(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " <> ?", Arrays.asList(value));
    }

    public static <T extends Comparable<T>> Condition gt(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " > ?", Arrays.asList(value));
    }

    public static <T extends Comparable<T>> Condition lt(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " < ?", Arrays.asList(value));
    }

    public static <T extends Comparable<T>> Condition ge(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " >= ?", Arrays.asList(value));
    }

    public static <T extends Comparable<T>> Condition le(TypedField<?, T> field, T value) {
        return new SimpleCondition(field.column() + " <= ?", Arrays.asList(value));
    }

    public static <T extends Comparable<T>> Condition between(TypedField<?, T> field, T low, T high) {
        return new SimpleCondition(field.column() + " BETWEEN ? AND ?", Arrays.asList(low, high));
    }

    public static <T> Condition in(TypedField<?, T> field, Collection<T> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN requires at least one value");
        }
        String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(", "));
        return new SimpleCondition(field.column() + " IN (" + placeholders + ")", new ArrayList<>(values));
    }

    public static Condition like(TypedField<?, String> field, String pattern) {
        return new SimpleCondition(field.column() + " LIKE ?", Arrays.asList(pattern));
    }

    public static Condition isNull(TypedField<?, ?> field) {
        return new SimpleCondition(field.column() + " IS NULL", Collections.emptyList());
    }

    public static Condition isNotNull(TypedField<?, ?> field) {
        return new SimpleCondition(field.column() + " IS NOT NULL", Collections.emptyList());
    }

    private static final class SimpleCondition implements Condition {
        private final String fragment;
        private final List<Object> bindings;

        SimpleCondition(String fragment, List<Object> bindings) {
            this.fragment = fragment;
            this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
        }

        @Override
        public String sqlFragment() {
            return fragment;
        }

        @Override
        public List<Object> bindings() {
            return bindings;
        }
    }
}
