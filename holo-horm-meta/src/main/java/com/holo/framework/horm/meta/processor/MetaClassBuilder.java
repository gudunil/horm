package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.EntityMeta;
import com.holo.framework.horm.meta.FieldAccessor;
import com.holo.framework.horm.meta.FieldMeta;
import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.RelationMeta;
import com.holo.framework.horm.meta.RelationType;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the {@code XxxMeta} companion class for each {@link EntityDescriptor}
 * using JavaPoet.
 *
 * <p>Emits static {@link FieldMeta}/{@link FieldAccessor} constants, a
 * {@code MAPPER} singleton reference, an {@code ALL_FIELDS} list, and the
 * {@code entityMeta()} factory method consumed by {@code EntityMetaRegistry}
 * (M1-6) at startup.
 */
public final class MetaClassBuilder {

    private MetaClassBuilder() {
    }

    public static void build(EntityDescriptor d, Filer filer) throws IOException {
        ClassName entity = ClassName.bestGuess(d.qualifiedName());
        ClassName meta = ClassName.get(d.generatedPackage(), d.simpleName() + "Meta");
        ClassName mapperClass = ClassName.get(d.generatedPackage(), d.simpleName() + "Mapper");

        ClassName fieldMetaCn = ClassName.get(FieldMeta.class);
        ClassName fieldAccCn = ClassName.get(FieldAccessor.class);
        ClassName entityMetaCn = ClassName.get(EntityMeta.class);
        ClassName mapperCn = ClassName.get(Mapper.class);
        ClassName genTypeCn = ClassName.get(GenerationType.class);

        TypeSpec.Builder type = TypeSpec.classBuilder(meta)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        type.addField(FieldSpec.builder(String.class, "TABLE_NAME",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$S", d.tableName())
            .build());

        type.addField(FieldSpec.builder(
                ParameterizedTypeName.get(ClassName.get(Class.class), entity), "ENTITY_TYPE",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.class", entity)
            .build());

        List<String> fieldConstNames = new ArrayList<>();
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            String constName = constName(f.name());
            fieldConstNames.add(constName);
            TypeName ft = TypeMapper.boxType(f);

            type.addField(buildFieldMetaConstant(fieldMetaCn, genTypeCn, f, constName, ft));

            TypeName accT = ParameterizedTypeName.get(fieldAccCn, entity, ft);
            type.addField(FieldSpec.builder(accT, constName + "_ACCESSOR",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.of($T::$L, $T::$L)",
                    fieldAccCn, entity, f.getterName(), entity, f.setterName())
                .build());
        }

        TypeName mapperT = ParameterizedTypeName.get(mapperCn, entity);
        type.addField(FieldSpec.builder(mapperT, "MAPPER",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.INSTANCE", mapperClass)
            .build());

        TypeName fieldMetaWildcard = ParameterizedTypeName.get(fieldMetaCn,
            WildcardTypeName.subtypeOf(Object.class));
        TypeName listT = ParameterizedTypeName.get(ClassName.get(List.class), fieldMetaWildcard);
        CodeBlock.Builder listInit = CodeBlock.builder().add("$T.of(", ClassName.get(List.class));
        for (int i = 0; i < fieldConstNames.size(); i++) {
            if (i > 0) {
                listInit.add(", ");
            }
            listInit.add("$L", fieldConstNames.get(i));
        }
        listInit.add(")");
        type.addField(FieldSpec.builder(listT, "ALL_FIELDS",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(listInit.build())
            .build());

        type.addField(buildAllRelationsConstant(d));

        type.addMethod(buildEntityMetaMethod(entityMetaCn, entity, d));

        JavaFile.builder(d.generatedPackage(), type.build())
            .indent("    ")
            .build()
            .writeTo(filer);
    }

    private static FieldSpec buildFieldMetaConstant(ClassName fieldMetaCn,
                                                    ClassName genTypeCn,
                                                    EntityDescriptor.FieldDescriptor f,
                                                    String constName,
                                                    TypeName ft) {
        TypeName fieldMetaT = ParameterizedTypeName.get(fieldMetaCn, ft);
        CodeBlock.Builder init = CodeBlock.builder()
            .add("$T.<$T>builder()", fieldMetaCn, ft)
            .add(".name($S)", f.name())
            .add(".column($S)", f.column())
            .add(".type($T.class)", ft);
        if (f.isId()) {
            init.add(".id(true)");
        }
        if (f.generationStrategy() != null) {
            init.add(".generationStrategy($T.$L)", genTypeCn, f.generationStrategy());
        }
        if (!f.nullable()) {
            init.add(".nullable(false)");
        }
        if (f.unique()) {
            init.add(".unique(true)");
        }
        if (f.length() != 255) {
            init.add(".length($L)", f.length());
        }
        if (f.precision() != 0) {
            init.add(".precision($L)", f.precision());
        }
        if (f.scale() != 0) {
            init.add(".scale($L)", f.scale());
        }
        if (!f.insertable()) {
            init.add(".insertable(false)");
        }
        if (!f.updatable()) {
            init.add(".updatable(false)");
        }
        init.add(".build()");
        return FieldSpec.builder(fieldMetaT, constName,
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(init.build())
            .build();
    }

    private static MethodSpec buildEntityMetaMethod(ClassName entityMetaCn,
                                                    ClassName entity,
                                                    EntityDescriptor d) {
        TypeName entityMetaT = ParameterizedTypeName.get(entityMetaCn, entity);
        CodeBlock.Builder body = CodeBlock.builder()
            .add("return $T.<$T>builder()", entityMetaCn, entity)
            .add(".type($T.class)", entity)
            .add(".tableName(TABLE_NAME)");
        if (d.schema() != null && !d.schema().isEmpty()) {
            body.add(".schema($S)", d.schema());
        }
        if (d.dataSource() != null && !d.dataSource().isEmpty()) {
            body.add(".dataSource($S)", d.dataSource());
        }
        body.add(".fields(ALL_FIELDS)");
        if (d.idField() != null) {
            body.add(".idField($L)", constName(d.idField().name()));
        }
        body.add(".mapper(MAPPER)")
            .add(".relations(ALL_RELATIONS)")
            .add(".build()");
        return MethodSpec.methodBuilder("entityMeta")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(entityMetaT)
            .addStatement(body.build())
            .build();
    }

    /**
     * Emits the {@code ALL_RELATIONS} constant — a {@code List<RelationMeta>}
     * with one entry per relation field parsed from {@code @BelongsTo}/
     * {@code @HasOne}/{@code @HasMany}/{@code @HasAndBelongsToMany}/
     * {@code @HasManyThrough}. Empty list when the entity has no relations,
     * preserving the M1/M2 behaviour.
     *
     * <p>Per relation, only the relevant builder methods are invoked:
     * {@code foreignKey} is emitted when non-null, {@code joinTable} only for
     * HABTM, {@code through} only for HAS_MANY_THROUGH.
     */
    private static FieldSpec buildAllRelationsConstant(EntityDescriptor d) {
        ClassName relationMetaCn = ClassName.get(RelationMeta.class);
        ClassName relationTypeCn = ClassName.get(RelationType.class);
        TypeName relationListT = ParameterizedTypeName.get(ClassName.get(List.class), relationMetaCn);

        CodeBlock.Builder init = CodeBlock.builder();
        if (d.relations().isEmpty()) {
            init.add("$T.of()", ClassName.get(List.class));
        } else {
            init.add("$T.of(", ClassName.get(List.class));
            for (int i = 0; i < d.relations().size(); i++) {
                if (i > 0) {
                    init.add(", ");
                }
                EntityDescriptor.RelationDescriptor r = d.relations().get(i);
                ClassName targetCn = ClassName.bestGuess(r.targetEntityQualifiedName());
                init.add("$T.builder().name($S).targetEntity($T.class).type($T.$L)",
                    relationMetaCn, r.name(), targetCn, relationTypeCn, r.type().name());
                if (r.foreignKey() != null) {
                    init.add(".foreignKey($S)", r.foreignKey());
                }
                if (r.associationForeignKey() != null) {
                    init.add(".associationForeignKey($S)", r.associationForeignKey());
                }
                if (r.joinTable() != null) {
                    init.add(".joinTable($S)", r.joinTable());
                }
                if (r.throughQualifiedName() != null) {
                    ClassName throughCn = ClassName.bestGuess(r.throughQualifiedName());
                    init.add(".through($T.class)", throughCn);
                }
                init.add(".build()");
            }
            init.add(")");
        }
        return FieldSpec.builder(relationListT, "ALL_RELATIONS",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer(init.build())
            .build();
    }

    private static String constName(String fieldName) {
        return EntityDescriptorParser.snake(fieldName).toUpperCase();
    }
}
