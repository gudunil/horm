package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.TransactionAdvisorProvider;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import com.holo.framework.horm.meta.annotation.Transactional;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    static String build(TypeElement type, Filer filer, ProcessingEnvironment processingEnv) {
        List<TransactionMethodInfo> methods = collectTransactionalMethods(type, processingEnv);
        if (methods.isEmpty()) {
            return null;
        }

        String qualifiedName = type.getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String packageName = qualifiedName.substring(0, qualifiedName.length() - simpleName.length() - 1);
        String advisorName = simpleName + "TransactionAdvisor";
        String generatedPackage = packageName + ".generated";

        TypeSpec advisorClass = buildAdvisorClass(advisorName, qualifiedName, methods);
        JavaFile javaFile = JavaFile.builder(generatedPackage, advisorClass).build();

        try {
            javaFile.writeTo(filer);
        } catch (IOException e) {
            // FilerException for duplicate writes is benign
        }

        return generatedPackage + "." + advisorName;
    }

    private static List<TransactionMethodInfo> collectTransactionalMethods(TypeElement type, ProcessingEnvironment processingEnv) {
        List<TransactionMethodInfo> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            AnnotationMirror txMirror = findAnnotationMirror(enclosed, Transactional.class.getName());
            if (txMirror != null && enclosed instanceof ExecutableElement method) {
                result.add(new TransactionMethodInfo(method.getSimpleName().toString(), txMirror, processingEnv));
            }
        }
        // Also check class-level @Transactional
        AnnotationMirror classTxMirror = findAnnotationMirror(type, Transactional.class.getName());
        if (classTxMirror != null && result.isEmpty()) {
            // Class-level annotation applies to all public methods;
            // for M4 we just record the class-level metadata as a
            // single entry with methodName = "*".
            result.add(new TransactionMethodInfo("*", classTxMirror, processingEnv));
        }
        return result;
    }

    private static AnnotationMirror findAnnotationMirror(Element element, String annotationQualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationQualifiedName)) {
                return mirror;
            }
        }
        return null;
    }

    private static TypeSpec buildAdvisorClass(String advisorName,
                                               String targetQualifiedName,
                                               List<TransactionMethodInfo> methods) {
        ClassName metaType = ClassName.get(
            "com.holo.framework.horm.meta", "TransactionMethodMeta");
        ParameterizedTypeName listType = ParameterizedTypeName.get(
            ClassName.get(List.class), metaType);

        CodeBlock.Builder init = CodeBlock.builder().add("$T.of(\n", List.class);
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) init.add(",\n");
            TransactionMethodInfo m = methods.get(i);
            init.add("  new $T($S, $T.$L, $T.$L, $L, $L, $L, $L)",
                metaType, m.methodName,
                Propagation.class, m.propagation.name(),
                Isolation.class, m.isolation.name(),
                m.timeout, m.readOnly,
                buildListLiteral(m.rollbackFor),
                buildListLiteral(m.noRollbackFor));
        }
        init.add("\n)");

        FieldSpec methodsField = FieldSpec.builder(listType, "METHODS",
                javax.lang.model.element.Modifier.PUBLIC,
                javax.lang.model.element.Modifier.STATIC,
                javax.lang.model.element.Modifier.FINAL)
            .initializer(init.build())
            .build();

        // methods() — implements TransactionAdvisorProvider
        MethodSpec methodsMethod = MethodSpec.methodBuilder("methods")
            .addAnnotation(Override.class)
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .returns(listType)
            .addStatement("return METHODS")
            .build();

        // targetClassName() — implements TransactionAdvisorProvider
        MethodSpec targetClassNameMethod = MethodSpec.methodBuilder("targetClassName")
            .addAnnotation(Override.class)
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return $S", targetQualifiedName)
            .build();

        return TypeSpec.classBuilder(advisorName)
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC,
                javax.lang.model.element.Modifier.FINAL)
            .addSuperinterface(ClassName.get(TransactionAdvisorProvider.class))
            .addField(methodsField)
            .addMethod(methodsMethod)
            .addMethod(targetClassNameMethod)
            .build();
    }

    private static final class TransactionMethodInfo {
        final String methodName;
        final Propagation propagation;
        final Isolation isolation;
        final int timeout;
        final boolean readOnly;
        final List<String> rollbackFor;
        final List<String> noRollbackFor;

        TransactionMethodInfo(String methodName, AnnotationMirror txMirror, ProcessingEnvironment processingEnv) {
            this.methodName = methodName;
            Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(txMirror);
            this.propagation = getEnumValue(values, "propagation", Propagation.REQUIRED, Propagation.class);
            this.isolation = getEnumValue(values, "isolation", Isolation.DEFAULT, Isolation.class);
            this.timeout = getIntValue(values, "timeout", -1);
            this.readOnly = getBooleanValue(values, "readOnly", false);
            this.rollbackFor = getClassArrayValues(values, "rollbackFor");
            this.noRollbackFor = getClassArrayValues(values, "noRollbackFor");
        }

        @SuppressWarnings("unchecked")
        private static <E extends Enum<E>> E getEnumValue(
                Map<? extends ExecutableElement, ? extends AnnotationValue> values,
                String key, E defaultValue, Class<E> enumType) {
            for (var entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(key)) {
                    Object val = entry.getValue().getValue();
                    if (val instanceof javax.lang.model.element.VariableElement ve) {
                        return Enum.valueOf(enumType, ve.getSimpleName().toString());
                    }
                }
            }
            return defaultValue;
        }

        private static int getIntValue(
                Map<? extends ExecutableElement, ? extends AnnotationValue> values,
                String key, int defaultValue) {
            for (var entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(key)) {
                    Object val = entry.getValue().getValue();
                    if (val instanceof Integer i) return i;
                }
            }
            return defaultValue;
        }

        private static boolean getBooleanValue(
                Map<? extends ExecutableElement, ? extends AnnotationValue> values,
                String key, boolean defaultValue) {
            for (var entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(key)) {
                    Object val = entry.getValue().getValue();
                    if (val instanceof Boolean b) return b;
                }
            }
            return defaultValue;
        }

        /**
         * Extracts all fully-qualified class names from a {@code Class<?>[]} annotation
         * attribute by walking the AnnotationMirror value list. This correctly handles
         * arrays with multiple elements (e.g. {@code rollbackFor = {A.class, B.class}}).
         */
        @SuppressWarnings("unchecked")
        private static List<String> getClassArrayValues(
                Map<? extends ExecutableElement, ? extends AnnotationValue> values,
                String key) {
            for (var entry : values.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(key)) {
                    Object val = entry.getValue().getValue();
                    if (val instanceof List<?> list) {
                        List<String> result = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof AnnotationValue av && av.getValue() instanceof TypeMirror tm) {
                                result.add(tm.toString());
                            }
                        }
                        return result;
                    }
                }
            }
            return List.of();
        }
    }

    /**
     * Build a CodeBlock representing a List.of(...) call with the given string literals.
     * Empty list becomes List.of(), single element becomes List.of("elem"),
     * multiple elements become List.of("elem1", "elem2", ...).
     */
    private static CodeBlock buildListLiteral(List<String> elements) {
        if (elements.isEmpty()) {
            return CodeBlock.of("$T.of()", List.class);
        }
        CodeBlock.Builder builder = CodeBlock.builder().add("$T.of(", List.class);
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) builder.add(", ");
            builder.add("$S", elements.get(i));
        }
        builder.add(")");
        return builder.build();
    }
}
