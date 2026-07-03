package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.query.BigDecimalField;
import com.holo.framework.horm.meta.query.BooleanField;
import com.holo.framework.horm.meta.query.EnumField;
import com.holo.framework.horm.meta.query.InstantField;
import com.holo.framework.horm.meta.query.IntegerField;
import com.holo.framework.horm.meta.query.LongField;
import com.holo.framework.horm.meta.query.StringField;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;

/**
 * Centralised mapping from a {@link EntityDescriptor.FieldDescriptor} to the
 * JavaPoet {@link TypeName}, {@code Row} accessor name, and {@code TypedField}
 * concrete class that the builders emit.
 *
 * <p>Keeping this logic in one place avoids scattering {@code switch} blocks
 * across {@code MetaClassBuilder}/{@code MapperBuilder}/{@code QueryMetaBuilder}
 * and makes the type-mapping table easy to audit and unit-test.
 */
final class TypeMapper {

    private TypeMapper() {
    }

    /**
     * Returns the boxed {@link TypeName} for a field — primitive types are
     * boxed because {@code FieldMeta<T>}/{@code FieldAccessor<T,V>} require
     * reference types as generic arguments.
     */
    static TypeName boxType(EntityDescriptor.FieldDescriptor f) {
        String tqn = f.typeQualifiedName();
        if (f.primitive()) {
            return switch (tqn) {
                case "long" -> TypeName.LONG.box();
                case "int" -> TypeName.INT.box();
                case "boolean" -> TypeName.BOOLEAN.box();
                case "byte" -> TypeName.BYTE.box();
                case "short" -> TypeName.SHORT.box();
                case "float" -> TypeName.FLOAT.box();
                case "double" -> TypeName.DOUBLE.box();
                case "char" -> TypeName.CHAR.box();
                default -> ClassName.bestGuess(tqn);
            };
        }
        if ("byte[]".equals(tqn)) {
            return ArrayTypeName.of(TypeName.BYTE);
        }
        return ClassName.bestGuess(tqn);
    }

    /**
     * Returns the {@code Row} getter method name (without the {@code row.} receiver)
     * for reading this field's column from a {@link com.holo.framework.horm.meta.Row}.
     */
    static String rowGetter(EntityDescriptor.FieldDescriptor f) {
        if (f.enumType()) {
            return "getEnum";
        }
        return switch (f.typeQualifiedName()) {
            case "long", "java.lang.Long" -> "getLong";
            case "int", "java.lang.Integer" -> "getInteger";
            case "java.lang.String" -> "getString";
            case "boolean", "java.lang.Boolean" -> "getBoolean";
            case "java.time.Instant" -> "getInstant";
            case "java.math.BigDecimal" -> "getBigDecimal";
            case "byte[]" -> "getBytes";
            default -> "get";
        };
    }

    /**
     * Returns the {@code Row} setter method name. Falls back to the generic
     * {@code set(column, Object)} for types without a typed setter.
     */
    static String rowSetter(EntityDescriptor.FieldDescriptor f) {
        return switch (f.typeQualifiedName()) {
            case "long", "java.lang.Long" -> "setLong";
            case "java.lang.String" -> "setString";
            case "boolean", "java.lang.Boolean" -> "setBoolean";
            case "java.time.Instant" -> "setInstant";
            default -> "set";
        };
    }

    /**
     * Returns the concrete {@code TypedField} subclass for this field, or
     * {@code null} if the type is not supported in the query DSL (in which
     * case {@code QueryMetaBuilder} skips emitting a constant for it).
     */
    static ClassName typedFieldClass(EntityDescriptor.FieldDescriptor f) {
        if (f.enumType()) {
            return ClassName.get(EnumField.class);
        }
        return switch (f.typeQualifiedName()) {
            case "long", "java.lang.Long" -> ClassName.get(LongField.class);
            case "int", "java.lang.Integer" -> ClassName.get(IntegerField.class);
            case "java.lang.String" -> ClassName.get(StringField.class);
            case "boolean", "java.lang.Boolean" -> ClassName.get(BooleanField.class);
            case "java.time.Instant" -> ClassName.get(InstantField.class);
            case "java.math.BigDecimal" -> ClassName.get(BigDecimalField.class);
            default -> null;
        };
    }
}
