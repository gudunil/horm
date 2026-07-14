package com.holo.framework.horm.meta.query.expr;

import com.holo.framework.horm.meta.query.TypedField;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Factory for scalar function {@link FuncExpr} / {@link ComparableExpr}
 * instances. Naming follows the {@link com.holo.framework.horm.meta.query.Conditions}
 * convention (plural noun, final class, private constructor, static methods only).
 */
public final class Functions {

    private Functions() {}

    // —— String functions ——

    public static ComparableExpr<String> upper(TypedField<?, String> field) {
        return new ScalarFuncExpr<>(FunctionType.UPPER, String.class,
            "UPPER(" + field.column() + ")", List.of(), null, List.of(field));
    }

    public static ComparableExpr<String> lower(TypedField<?, String> field) {
        return new ScalarFuncExpr<>(FunctionType.LOWER, String.class,
            "LOWER(" + field.column() + ")", List.of(), null, List.of(field));
    }

    public static ComparableExpr<String> trim(TypedField<?, String> field) {
        return new ScalarFuncExpr<>(FunctionType.TRIM, String.class,
            "TRIM(" + field.column() + ")", List.of(), null, List.of(field));
    }

    public static ComparableExpr<String> substring(TypedField<?, String> field, int start, int length) {
        return new ScalarFuncExpr<>(FunctionType.SUBSTRING, String.class,
            "SUBSTRING(" + field.column() + ", " + start + ", " + length + ")",
            List.of(), null, List.of(field));
    }

    /** Concatenates multiple expressions. Accepts {@code Expr<?>} arguments, so
     *  {@code TypedField} constants (which implement {@code Expr}) can be passed directly. */
    public static ComparableExpr<String> concat(Expr<?>... parts) {
        StringBuilder fragment = new StringBuilder("CONCAT(");
        List<Object> allBindings = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) fragment.append(", ");
            fragment.append(parts[i].sqlFragment());
            allBindings.addAll(parts[i].bindings());
        }
        fragment.append(")");
        return new ScalarFuncExpr<>(FunctionType.CONCAT, String.class,
            fragment.toString(), Collections.unmodifiableList(allBindings), null,
            Arrays.asList(parts));
    }

    public static ComparableExpr<Integer> length(TypedField<?, String> field) {
        return new ScalarFuncExpr<>(FunctionType.LENGTH, Integer.class,
            "LENGTH(" + field.column() + ")", List.of(), null, List.of(field));
    }

    // —— Math functions ——

    public static <N extends Number & Comparable<N>> ComparableExpr<N> abs(TypedField<?, N> field) {
        return new ScalarFuncExpr<>(FunctionType.ABS, field.type(),
            "ABS(" + field.column() + ")", List.of(), null, List.of(field));
    }

    @SuppressWarnings("unchecked")
    public static <N extends Number & Comparable<N>> ComparableExpr<N> round(TypedField<?, N> field, int scale) {
        return new ScalarFuncExpr<>(FunctionType.ROUND, field.type(),
            "ROUND(" + field.column() + ", " + scale + ")", List.of(), null, List.of(field));
    }

    @SuppressWarnings("unchecked")
    public static <N extends Number & Comparable<N>> ComparableExpr<N> floor(TypedField<?, N> field) {
        return new ScalarFuncExpr<>(FunctionType.FLOOR, field.type(),
            "FLOOR(" + field.column() + ")", List.of(), null, List.of(field));
    }

    @SuppressWarnings("unchecked")
    public static <N extends Number & Comparable<N>> ComparableExpr<N> ceil(TypedField<?, N> field) {
        return new ScalarFuncExpr<>(FunctionType.CEIL, field.type(),
            "CEIL(" + field.column() + ")", List.of(), null, List.of(field));
    }

    // —— Date functions ——

    /** NOW() / CURRENT_TIMESTAMP. Returns Instant; Row.getInstant handles type conversion. */
    public static ComparableExpr<Instant> now() {
        return new ScalarFuncExpr<>(FunctionType.NOW, Instant.class,
            "NOW()", List.of(), null, List.of());
    }

    /**
     * Date formatting. Pattern uses Java date format (e.g. {@code "yyyy-MM-dd"});
     * the Dialect layer translates to database-native format at render time,
     * and the translated pattern flows as a binding (prevents SQL injection).
     */
    public static ComparableExpr<String> dateFormat(TypedField<?, Instant> field, String pattern) {
        return new ScalarFuncExpr<>(FunctionType.DATE_FORMAT, String.class,
            "DATE_FORMAT(" + field.column() + ", ?)", List.of(pattern), null, List.of(field));
    }

    public static ComparableExpr<Integer> year(TypedField<?, Instant> field) {
        return new ScalarFuncExpr<>(FunctionType.YEAR, Integer.class,
            "YEAR(" + field.column() + ")", List.of(), null, List.of(field));
    }

    public static ComparableExpr<Integer> month(TypedField<?, Instant> field) {
        return new ScalarFuncExpr<>(FunctionType.MONTH, Integer.class,
            "MONTH(" + field.column() + ")", List.of(), null, List.of(field));
    }

    public static ComparableExpr<Integer> day(TypedField<?, Instant> field) {
        return new ScalarFuncExpr<>(FunctionType.DAY, Integer.class,
            "DAY(" + field.column() + ")", List.of(), null, List.of(field));
    }

    // —— Control flow functions ——

    public static <T> Expr<T> coalesce(Expr<T> first, Expr<T> second) {
        List<Object> bindings = new ArrayList<>(first.bindings().size() + second.bindings().size());
        bindings.addAll(first.bindings());
        bindings.addAll(second.bindings());
        return new SimpleExpr<>(first.javaType(),
            "COALESCE(" + first.sqlFragment() + ", " + second.sqlFragment() + ")",
            Collections.unmodifiableList(bindings), null);
    }

    public static <T> Expr<T> nullif(Expr<T> a, Expr<T> b) {
        List<Object> bindings = new ArrayList<>(a.bindings().size() + b.bindings().size());
        bindings.addAll(a.bindings());
        bindings.addAll(b.bindings());
        return new SimpleExpr<>(a.javaType(),
            "NULLIF(" + a.sqlFragment() + ", " + b.sqlFragment() + ")",
            Collections.unmodifiableList(bindings), null);
    }

    // —— Alias support ——

    /**
     * Attach an alias to any expression (for projection naming).
     * Returns a new immutable {@code Expr}; the original is unchanged.
     */
    public static <T> Expr<T> alias(Expr<T> expr, String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        return new AliasedExpr<>(expr, alias);
    }

    // —— Escape hatch ——

    /**
     * Bypass type safety and embed a raw SQL fragment.
     * <p><strong>WARNING:</strong> No compile-time validation; user is
     * responsible for matching placeholders with bindings.
     */
    public static <T> Expr<T> raw(String sqlFragment, Class<T> returnType, Object... bindings) {
        return new SimpleExpr<>(returnType, sqlFragment, Arrays.asList(bindings), null);
    }

    // —— Internal implementations ——

    private static final class ScalarFuncExpr<T extends Comparable<T>>
            implements ComparableExpr<T>, FuncExpr<T> {
        private final FunctionType functionType;
        private final Class<T> javaType;
        private final String sqlFragment;
        private final List<Object> bindings;
        private final String alias;
        private final List<Expr<?>> arguments;

        ScalarFuncExpr(FunctionType functionType, Class<T> javaType,
                       String sqlFragment, List<Object> bindings, String alias) {
            this(functionType, javaType, sqlFragment, bindings, alias, List.of());
        }

        ScalarFuncExpr(FunctionType functionType, Class<T> javaType,
                       String sqlFragment, List<Object> bindings, String alias,
                       List<Expr<?>> arguments) {
            this.functionType = functionType;
            this.javaType = javaType;
            this.sqlFragment = sqlFragment;
            this.bindings = bindings;
            this.alias = alias;
            this.arguments = arguments;
        }

        @Override public FunctionType functionType() { return functionType; }
        @Override public List<Expr<?>> arguments() { return arguments; }
        @Override public String sqlFragment() { return sqlFragment; }
        @Override public List<Object> bindings() { return bindings; }
        @Override public Class<T> javaType() { return javaType; }
        @Override public String alias() { return alias; }
    }

    static final class SimpleExpr<T> implements Expr<T> {
        private final Class<T> javaType;
        private final String sqlFragment;
        private final List<Object> bindings;
        private final String alias;

        SimpleExpr(Class<T> javaType, String sqlFragment, List<Object> bindings, String alias) {
            this.javaType = javaType;
            this.sqlFragment = sqlFragment;
            this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
            this.alias = alias;
        }

        @Override public String sqlFragment() { return sqlFragment; }
        @Override public List<Object> bindings() { return bindings; }
        @Override public Class<T> javaType() { return javaType; }
        @Override public String alias() { return alias; }
    }

    private static final class AliasedExpr<T> implements Expr<T>, FuncExpr<T> {
        private final Expr<T> delegate;
        private final String alias;

        AliasedExpr(Expr<T> delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override public String sqlFragment() { return delegate.sqlFragment(); }
        @Override public List<Object> bindings() { return delegate.bindings(); }
        @Override public Class<T> javaType() { return delegate.javaType(); }
        @Override public String alias() { return alias; }

        @Override
        public FunctionType functionType() {
            return delegate instanceof FuncExpr<?> f ? f.functionType() : null;
        }

        @Override
        public List<Expr<?>> arguments() {
            return delegate instanceof FuncExpr<?> f ? f.arguments() : List.of();
        }
    }
}
