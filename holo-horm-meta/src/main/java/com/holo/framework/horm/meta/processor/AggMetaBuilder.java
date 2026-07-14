package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.query.expr.Aggregates;
import com.holo.framework.horm.meta.query.expr.AggExpr;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Generates the {@code XxxAggMeta} companion class — a container of
 * type-safe aggregate expression constants for the query DSL.
 *
 * <p>Per entity, generates:
 * <ul>
 *   <li>{@code COUNT} — {@code Aggregates.count()} (COUNT(*))</li>
 *   <li>{@code COUNT_<ID>} — {@code Aggregates.count(XxxQueryMeta.ID)}</li>
 *   <li>For numeric fields: {@code SUM_<FIELD>}, {@code AVG_<FIELD>},
 *       {@code MAX_<FIELD>}, {@code MIN_<FIELD>}</li>
 *   <li>For comparable fields: {@code MAX_<FIELD>}, {@code MIN_<FIELD>}</li>
 * </ul>
 *
 * <p>Constant names use {@code snake(fieldName).toUpperCase()}, consistent
 * with {@link QueryMetaBuilder#constName(String)}.
 */
public final class AggMetaBuilder {

    private static final Set<String> NUMERIC_TYPES = Set.of(
        "long", "java.lang.Long",
        "int", "java.lang.Integer",
        "short", "java.lang.Short",
        "byte", "java.lang.Byte",
        "float", "java.lang.Float",
        "double", "java.lang.Double",
        "java.math.BigDecimal",
        "java.math.BigInteger"
    );

    private static final Set<String> COMPARABLE_TYPES = Set.of(
        "long", "java.lang.Long",
        "int", "java.lang.Integer",
        "short", "java.lang.Short",
        "byte", "java.lang.Byte",
        "float", "java.lang.Float",
        "double", "java.lang.Double",
        "java.math.BigDecimal",
        "java.math.BigInteger",
        "java.lang.String",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime"
    );

    private AggMetaBuilder() {
    }

    public static void build(EntityDescriptor d, Filer filer) throws IOException {
        ClassName queryMeta = ClassName.get(d.generatedPackage(), d.simpleName() + "QueryMeta");
        ClassName aggMeta = ClassName.get(d.generatedPackage(), d.simpleName() + "AggMeta");
        ClassName aggExprClass = ClassName.get(AggExpr.class);
        ClassName aggregtesClass = ClassName.get(Aggregates.class);

        TypeSpec.Builder type = TypeSpec.classBuilder(aggMeta)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        // Private constructor
        type.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build());

        // COUNT(*) — always generated
        TypeName countType = ParameterizedTypeName.get(aggExprClass, ClassName.get(Long.class));
        type.addField(FieldSpec.builder(countType, "COUNT",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.count()", aggregtesClass)
            .build());

        // COUNT(<id>) — using QueryMeta's id constant
        if (d.idField() != null) {
            String idConstName = constName(d.idField().name());
            type.addField(FieldSpec.builder(countType, "COUNT_" + idConstName,
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.count($T." + idConstName + ")", aggregtesClass, queryMeta)
                .build());
        }

        // Per-field aggregate constants
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            if (f.isId()) continue; // id already handled above
            if (TypeMapper.typedFieldClass(f) == null) continue; // skip unsupported types

            String constName = constName(f.name());
            String fieldRef = queryMeta.simpleName() + "." + constName;
            String tqn = f.typeQualifiedName();

            boolean isNumeric = NUMERIC_TYPES.contains(tqn);
            boolean isComparable = COMPARABLE_TYPES.contains(tqn);

            if (isNumeric) {
                TypeName sumAvgType = ParameterizedTypeName.get(aggExprClass, ClassName.get(BigDecimal.class));
                TypeName maxMinType = resolveAggType(tqn, aggExprClass);

                type.addField(FieldSpec.builder(sumAvgType, "SUM_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.sum($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
                type.addField(FieldSpec.builder(sumAvgType, "AVG_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.avg($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
                type.addField(FieldSpec.builder(maxMinType, "MAX_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.max($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
                type.addField(FieldSpec.builder(maxMinType, "MIN_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.min($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
            } else if (isComparable) {
                TypeName maxMinType = resolveAggType(tqn, aggExprClass);
                type.addField(FieldSpec.builder(maxMinType, "MAX_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.max($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
                type.addField(FieldSpec.builder(maxMinType, "MIN_" + constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.min($T." + constName + ")", aggregtesClass, queryMeta)
                    .build());
            }
        }

        JavaFile.builder(d.generatedPackage(), type.build())
            .indent("    ")
            .build()
            .writeTo(filer);
    }

    private static TypeName resolveAggType(String tqn, ClassName aggExprClass) {
        TypeName boxedType;
        if (NUMERIC_TYPES.contains(tqn) && !tqn.equals("java.math.BigDecimal") && !tqn.equals("java.math.BigInteger")) {
            // Integer/Long/Short/Byte/Float/Double primitives → max/min returns same type
            boxedType = switch (tqn) {
                case "long", "java.lang.Long" -> ClassName.get(Long.class);
                case "int", "java.lang.Integer" -> ClassName.get(Integer.class);
                case "short", "java.lang.Short" -> ClassName.get(Short.class);
                case "byte", "java.lang.Byte" -> ClassName.get(Byte.class);
                case "float", "java.lang.Float" -> ClassName.get(Float.class);
                case "double", "java.lang.Double" -> ClassName.get(Double.class);
                default -> ClassName.get(BigDecimal.class);
            };
        } else if (tqn.equals("java.math.BigDecimal") || tqn.equals("java.math.BigInteger")) {
            boxedType = ClassName.get(BigDecimal.class);
        } else if (tqn.equals("java.lang.String")) {
            boxedType = ClassName.get(String.class);
        } else if (tqn.equals("java.time.Instant")) {
            boxedType = ClassName.get(java.time.Instant.class);
        } else {
            boxedType = ClassName.bestGuess(tqn);
        }
        return ParameterizedTypeName.get(aggExprClass, boxedType);
    }

    private static String constName(String fieldName) {
        return EntityDescriptorParser.snake(fieldName).toUpperCase();
    }
}
