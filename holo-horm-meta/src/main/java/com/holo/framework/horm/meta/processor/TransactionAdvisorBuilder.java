package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import com.holo.framework.horm.meta.annotation.Transactional;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates {@code XxxTransactionAdvisor} classes for types that contain
 * methods annotated with {@link Transactional}.
 *
 * <p>Each generated advisor holds a {@code List<TransactionMethodMeta>}
 * constant describing the transactional methods found on the original class.
 */
final class TransactionAdvisorBuilder {

    private TransactionAdvisorBuilder() {
    }

    /**
     * Scans the given type element for {@link Transactional} methods and
     * generates a companion advisor class if any are found.
     *
     * @return the fully qualified name of the generated advisor, or
     *         {@code null} if the type has no transactional methods
     */
    static String build(TypeElement type, Filer filer) {
        List<TransactionMethodInfo> methods = collectTransactionalMethods(type);
        if (methods.isEmpty()) {
            return null;
        }

        String qualifiedName = type.getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String packageName = qualifiedName.substring(0, qualifiedName.length() - simpleName.length() - 1);
        String advisorName = simpleName + "TransactionAdvisor";
        String generatedPackage = packageName + ".generated";

        TypeSpec advisorClass = buildAdvisorClass(advisorName, methods);
        JavaFile javaFile = JavaFile.builder(generatedPackage, advisorClass).build();

        try {
            javaFile.writeTo(filer);
        } catch (IOException e) {
            // FilerException for duplicate writes is benign
        }

        return generatedPackage + "." + advisorName;
    }

    private static List<TransactionMethodInfo> collectTransactionalMethods(TypeElement type) {
        List<TransactionMethodInfo> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            Transactional tx = enclosed.getAnnotation(Transactional.class);
            if (tx != null && enclosed instanceof ExecutableElement method) {
                result.add(new TransactionMethodInfo(method.getSimpleName().toString(), tx));
            }
        }
        // Also check class-level @Transactional
        Transactional classTx = type.getAnnotation(Transactional.class);
        if (classTx != null && result.isEmpty()) {
            // Class-level annotation applies to all public methods;
            // for M4 we just record the class-level metadata as a
            // single entry with methodName = "*".
            result.add(new TransactionMethodInfo("*", classTx));
        }
        return result;
    }

    private static TypeSpec buildAdvisorClass(String advisorName,
                                               List<TransactionMethodInfo> methods) {
        ClassName metaType = ClassName.get(
            "com.holo.framework.horm.meta", "TransactionMethodMeta");
        ParameterizedTypeName listType = ParameterizedTypeName.get(
            ClassName.get(List.class), metaType);

        CodeBlock.Builder init = CodeBlock.builder().add("$T.of(\n", List.class);
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) init.add(",\n");
            TransactionMethodInfo m = methods.get(i);
            init.add("  new $T($S, $T.$L, $T.$L, $L, $L)",
                metaType, m.methodName,
                Propagation.class, m.propagation.name(),
                Isolation.class, m.isolation.name(),
                m.timeout, m.readOnly);
        }
        init.add("\n)");

        FieldSpec methodsField = FieldSpec.builder(listType, "METHODS",
                javax.lang.model.element.Modifier.PUBLIC,
                javax.lang.model.element.Modifier.STATIC,
                javax.lang.model.element.Modifier.FINAL)
            .initializer(init.build())
            .build();

        return TypeSpec.classBuilder(advisorName)
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC,
                javax.lang.model.element.Modifier.FINAL)
            .addField(methodsField)
            .build();
    }

    private static final class TransactionMethodInfo {
        final String methodName;
        final Propagation propagation;
        final Isolation isolation;
        final int timeout;
        final boolean readOnly;

        TransactionMethodInfo(String methodName, Transactional tx) {
            this.methodName = methodName;
            this.propagation = tx.propagation();
            this.isolation = tx.isolation();
            this.timeout = tx.timeout();
            this.readOnly = tx.readOnly();
        }
    }
}
