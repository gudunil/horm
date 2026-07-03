package com.holo.framework.horm.meta.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;

/**
 * Generates the {@code XxxQueryMeta} companion class — a container of
 * type-safe {@code TypedField} constants for the query DSL.
 *
 * <p>M1-4 emits only field constants (no condition builders); the
 * {@code eq}/{@code ne}/{@code gt}/{@code lt}/{@code like}/{@code in}/
 * {@code between}/{@code asc}/{@code desc} methods arrive in M2.
 *
 * <p>Fields whose type is not supported by any {@code TypedField} subclass
 * (e.g. {@code byte[]}) are silently skipped — no constant is emitted for
 * them, and the {@code XxxQueryMeta} class still compiles.
 */
public final class QueryMetaBuilder {

    private QueryMetaBuilder() {
    }

    public static void build(EntityDescriptor d, Filer filer) throws IOException {
        ClassName entity = ClassName.bestGuess(d.qualifiedName());
        ClassName queryMeta = ClassName.get(d.generatedPackage(), d.simpleName() + "QueryMeta");

        TypeSpec.Builder type = TypeSpec.classBuilder(queryMeta)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            ClassName tfClass = TypeMapper.typedFieldClass(f);
            if (tfClass == null) {
                continue;
            }
            String constName = constName(f.name());
            if (f.enumType()) {
                TypeName enumT = ClassName.bestGuess(f.enumQualifiedName());
                TypeName fieldT = ParameterizedTypeName.get(tfClass, entity, enumT);
                CodeBlock init = CodeBlock.of("$T.of($T.class, $S, $S, $T.class)",
                    tfClass, entity, f.name(), f.column(), enumT);
                type.addField(FieldSpec.builder(fieldT, constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(init)
                    .build());
            } else {
                TypeName fieldT = ParameterizedTypeName.get(tfClass, entity);
                CodeBlock init = CodeBlock.of("$T.of($T.class, $S, $S)",
                    tfClass, entity, f.name(), f.column());
                type.addField(FieldSpec.builder(fieldT, constName,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(init)
                    .build());
            }
        }

        JavaFile.builder(d.generatedPackage(), type.build())
            .indent("    ")
            .build()
            .writeTo(filer);
    }

    private static String constName(String fieldName) {
        return EntityDescriptorParser.snake(fieldName).toUpperCase();
    }
}
