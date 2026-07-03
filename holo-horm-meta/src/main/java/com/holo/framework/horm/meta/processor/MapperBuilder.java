package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.Mapper;
import com.holo.framework.horm.meta.Row;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;

/**
 * Generates the {@code XxxMapper} companion class — a package-private
 * {@link Mapper} implementation that materialises entities from a {@link Row}
 * and serialises them back, calling getters/setters directly with no
 * reflection.
 *
 * <p>Field dispatch by name uses a JDK 17 {@code switch} expression, which
 * javac lowers to {@code tableswitch}/{@code lookupswitch} for O(1) access.
 */
public final class MapperBuilder {

    private MapperBuilder() {
    }

    public static void build(EntityDescriptor d, Filer filer) throws IOException {
        ClassName entity = ClassName.bestGuess(d.qualifiedName());
        ClassName mapper = ClassName.get(d.generatedPackage(), d.simpleName() + "Mapper");
        ClassName meta = ClassName.get(d.generatedPackage(), d.simpleName() + "Meta");
        ClassName rowCn = ClassName.get(Row.class);

        TypeSpec.Builder type = TypeSpec.classBuilder(mapper)
            .addModifiers(Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Mapper.class), entity));

        type.addField(FieldSpec.builder(mapper, "INSTANCE",
                Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T()", mapper)
            .build());

        type.addMethod(buildMapMethod(d, entity, rowCn, meta));
        type.addMethod(buildToRowMethod(d, entity, rowCn, meta));
        type.addMethod(buildGetIdMethod(d, entity));
        type.addMethod(buildSetIdMethod(d, entity));
        type.addMethod(buildGetFieldMethod(d, entity));
        type.addMethod(buildSetFieldMethod(d, entity));

        JavaFile.builder(d.generatedPackage(), type.build())
            .indent("    ")
            .build()
            .writeTo(filer);
    }

    private static MethodSpec buildMapMethod(EntityDescriptor d, ClassName entity, ClassName rowCn, ClassName meta) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("map")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(entity)
            .addParameter(rowCn, "row");
        m.addStatement("$T u = new $T()", entity, entity);
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            String getter = TypeMapper.rowGetter(f);
            String col = f.column();
            if ("getEnum".equals(getter)) {
                TypeName enumT = ClassName.bestGuess(f.enumQualifiedName());
                m.addStatement("if (row.has($S)) u.$L(row.getEnum($S, $T.class))",
                    col, f.setterName(), col, enumT);
            } else if ("get".equals(getter)) {
                m.addStatement("if (row.has($S)) u.$L(row.get($S))",
                    col, f.setterName(), col);
            } else {
                m.addStatement("if (row.has($S)) u.$L(row.$L($S))",
                    col, f.setterName(), getter, col);
            }
        }
        m.addStatement("return u");
        return m.build();
    }

    private static MethodSpec buildToRowMethod(EntityDescriptor d, ClassName entity, ClassName rowCn, ClassName meta) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("toRow")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(rowCn)
            .addParameter(entity, "u");
        m.addStatement("$T row = $T.create($T.TABLE_NAME)", rowCn, rowCn, meta);
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            String setter = TypeMapper.rowSetter(f);
            String col = f.column();
            if ("set".equals(setter)) {
                m.addStatement("row.set($S, u.$L())", col, f.getterName());
            } else {
                m.addStatement("if (u.$L() != null) row.$L($S, u.$L())",
                    f.getterName(), setter, col, f.getterName());
            }
        }
        m.addStatement("return row");
        return m.build();
    }

    private static MethodSpec buildGetIdMethod(EntityDescriptor d, ClassName entity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("getId")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(Object.class)
            .addParameter(entity, "u");
        if (d.idField() == null) {
            m.addStatement("throw new $T($S)",
                ClassName.get(UnsupportedOperationException.class),
                "Entity " + d.simpleName() + " has no @Id field");
        } else {
            m.addStatement("return u.$L()", d.idField().getterName());
        }
        return m.build();
    }

    private static MethodSpec buildSetIdMethod(EntityDescriptor d, ClassName entity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("setId")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(entity, "u")
            .addParameter(Object.class, "id");
        if (d.idField() == null) {
            m.addStatement("throw new $T($S)",
                ClassName.get(UnsupportedOperationException.class),
                "Entity " + d.simpleName() + " has no @Id field");
        } else {
            TypeName idT = TypeMapper.boxType(d.idField());
            m.addStatement("u.$L(($T) id)", d.idField().setterName(), idT);
        }
        return m.build();
    }

    private static MethodSpec buildGetFieldMethod(EntityDescriptor d, ClassName entity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("getField")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(Object.class)
            .addParameter(entity, "u")
            .addParameter(String.class, "field");
        CodeBlock.Builder sw = CodeBlock.builder().add("return switch (field) {\n");
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            sw.add("  case $S -> u.$L();\n", f.name(), f.getterName());
        }
        sw.add("  default -> throw new $T($S + field);\n};",
            ClassName.get(IllegalArgumentException.class), "Unknown field: ");
        m.addCode(sw.build());
        return m.build();
    }

    private static MethodSpec buildSetFieldMethod(EntityDescriptor d, ClassName entity) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("setField")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(entity, "u")
            .addParameter(String.class, "field")
            .addParameter(Object.class, "value");
        CodeBlock.Builder sw = CodeBlock.builder().add("switch (field) {\n");
        for (EntityDescriptor.FieldDescriptor f : d.fields()) {
            TypeName ft = TypeMapper.boxType(f);
            sw.add("  case $S -> u.$L(($T) value);\n", f.name(), f.setterName(), ft);
        }
        sw.add("  default -> throw new $T($S + field);\n}",
            ClassName.get(IllegalArgumentException.class), "Unknown field: ");
        m.addCode(sw.build());
        return m.build();
    }
}
