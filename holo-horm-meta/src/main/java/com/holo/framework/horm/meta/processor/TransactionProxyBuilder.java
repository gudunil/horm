package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.TransactionMethodMeta;
import com.holo.framework.horm.meta.TransactionProxyFactory;
import com.holo.framework.horm.meta.annotation.Isolation;
import com.holo.framework.horm.meta.annotation.Propagation;
import com.holo.framework.horm.meta.annotation.Transactional;
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
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates APT-based transaction proxy subclasses for classes containing
 * {@link Transactional} methods, eliminating runtime reflection from the
 * transaction hot path.
 *
 * <p>For each class with {@code @Transactional} methods, this builder emits:
 * <ul>
 *   <li>{@code Xxx$TransactionalProxy} — a proxy subclass that overrides each
 *       transactional method with inlined begin/commit/rollback logic and
 *       delegates non-transactional methods directly</li>
 *   <li>{@code Xxx$TransactionalProxyFactory} — a factory class implementing
 *       {@link TransactionProxyFactory}, discoverable via {@link java.util.ServiceLoader}</li>
 * </ul>
 */
final class TransactionProxyBuilder {

    private TransactionProxyBuilder() {
    }

    /**
     * Generates the transaction proxy subclass and factory class for the given type.
     *
     * @return the fully qualified name of the generated factory class, or
     *         {@code null} if the type has no transactional methods or cannot
     *         be proxied (e.g. final class)
     */
    static String build(TypeElement type, Filer filer, ProcessingEnvironment processingEnv) {
        List<ProxyMethodInfo> txMethods = collectTransactionalMethods(type, processingEnv);
        if (txMethods.isEmpty()) {
            return null;
        }

        // Cannot extend final classes
        if (type.getModifiers().contains(Modifier.FINAL)) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "@Transactional on final class " + type.getQualifiedName()
                    + " — APT proxy cannot be generated; will fall back to runtime bridge",
                type
            );
            return null;
        }

        String qualifiedName = type.getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String packageName = derivePackage(qualifiedName, simpleName);
        String generatedPackage = packageName + ".generated";

        // Collect non-transactional public methods for delegation
        List<ExecutableElement> nonTxMethods = collectNonTransactionalMethods(type);

        // Generate proxy subclass
        ClassName targetCn = ClassName.bestGuess(qualifiedName);
        String proxySimpleName = simpleName + "_TransactionalProxy";
        ClassName proxyCn = ClassName.get(generatedPackage, proxySimpleName);

        TypeSpec proxyClass;
        try {
            proxyClass = buildProxyClass(proxyCn, targetCn, txMethods, nonTxMethods, processingEnv);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "Failed to build proxy class for " + qualifiedName + ": " + e.getMessage(),
                type
            );
            return null;
        }
        writeJavaFile(generatedPackage, proxyClass, filer);

        // Generate factory class
        String factorySimpleName = simpleName + "_TransactionalProxyFactory";
        TypeSpec factoryClass = buildFactoryClass(factorySimpleName, targetCn, proxyCn, generatedPackage);
        writeJavaFile(generatedPackage, factoryClass, filer);

        return generatedPackage + "." + factorySimpleName;
    }

    private static List<ProxyMethodInfo> collectTransactionalMethods(TypeElement type, ProcessingEnvironment processingEnv) {
        List<ProxyMethodInfo> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            AnnotationMirror txMirror = findAnnotationMirror(enclosed, Transactional.class.getName());
            if (txMirror != null && enclosed instanceof ExecutableElement method) {
                // Skip final methods — cannot override
                if (method.getModifiers().contains(Modifier.FINAL)) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "@Transactional on final method " + method.getSimpleName()
                            + " — APT proxy cannot override; will fall back to runtime bridge",
                        method
                    );
                    continue;
                }
                // Skip static methods — cannot override
                if (method.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                // Skip private methods — cannot override
                if (method.getModifiers().contains(Modifier.PRIVATE)) {
                    continue;
                }
                result.add(new ProxyMethodInfo(method, txMirror, processingEnv));
            }
        }

        // Class-level @Transactional: if no method-level annotations found, treat all
        // public non-static non-final methods as transactional with class-level config
        AnnotationMirror classTxMirror = findAnnotationMirror(type, Transactional.class.getName());
        if (classTxMirror != null && result.isEmpty()) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) {
                    continue;
                }
                if (enclosed instanceof ExecutableElement method
                    && !method.getModifiers().contains(Modifier.STATIC)
                    && !method.getModifiers().contains(Modifier.FINAL)
                    && !method.getModifiers().contains(Modifier.PRIVATE)
                    && method.getModifiers().contains(Modifier.PUBLIC)) {
                    result.add(new ProxyMethodInfo(method, classTxMirror, processingEnv));
                }
            }
        }

        return result;
    }

    private static List<ExecutableElement> collectNonTransactionalMethods(TypeElement type) {
        List<ExecutableElement> result = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (enclosed instanceof ExecutableElement method
                && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.FINAL)
                && !method.getModifiers().contains(Modifier.PRIVATE)
                && method.getModifiers().contains(Modifier.PUBLIC)) {
                // Only include if not already a transactional method
                AnnotationMirror txMirror = findAnnotationMirror(method, Transactional.class.getName());
                if (txMirror == null) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private static TypeSpec buildProxyClass(ClassName proxyCn, ClassName targetCn,
                                            List<ProxyMethodInfo> txMethods,
                                            List<ExecutableElement> nonTxMethods,
                                            ProcessingEnvironment processingEnv) {
        TypeSpec.Builder builder = TypeSpec.classBuilder(proxyCn)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(targetCn);

        // delegate field
        builder.addField(FieldSpec.builder(targetCn, "delegate",
                Modifier.PRIVATE, Modifier.FINAL)
            .build());

        // context field (Object to avoid meta→core dependency)
        builder.addField(FieldSpec.builder(Object.class, "ctx",
                Modifier.PRIVATE, Modifier.FINAL)
            .build());

        // TransactionMethodMeta constants for each transactional method
        ClassName metaCn = ClassName.get(TransactionMethodMeta.class);
        for (int i = 0; i < txMethods.size(); i++) {
            ProxyMethodInfo m = txMethods.get(i);
            String constName = metaConstName(m.method.getSimpleName().toString());
            builder.addField(FieldSpec.builder(metaCn, constName,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(buildMetaInitializer(m))
                .build());
        }

        // Constructor
        MethodSpec constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(targetCn, "delegate")
            .addParameter(Object.class, "ctx")
            .addStatement("this.delegate = delegate")
            .addStatement("this.ctx = ctx")
            .build();
        builder.addMethod(constructor);

        // Override transactional methods
        for (ProxyMethodInfo m : txMethods) {
            MethodSpec method = buildTransactionalMethod(m, targetCn, processingEnv);
            if (method != null) {
                builder.addMethod(method);
            }
        }

        // Delegate non-transactional public methods
        for (ExecutableElement m : nonTxMethods) {
            builder.addMethod(buildDelegateMethod(m, targetCn));
        }

        // Rethrow helper
        builder.addMethod(buildRethrowMethod());

        return builder.build();
    }

    private static CodeBlock buildMetaInitializer(ProxyMethodInfo m) {
        return CodeBlock.builder()
            .add("new $T($S, $T.$L, $T.$L, $L, $L, $L, $L)",
                TransactionMethodMeta.class,
                m.methodName,
                Propagation.class, m.propagation.name(),
                Isolation.class, m.isolation.name(),
                m.timeout, m.readOnly,
                buildListLiteral(m.rollbackFor),
                buildListLiteral(m.noRollbackFor))
            .build();
    }

    private static MethodSpec buildTransactionalMethod(ProxyMethodInfo m, ClassName targetCn,
                                                       ProcessingEnvironment processingEnv) {
        ExecutableElement method = m.method;
        String methodName = method.getSimpleName().toString();
        String constName = metaConstName(methodName);

        // Return type
        TypeName returnType = TypeName.get(method.getReturnType());

        // Check if method name matches the proxy class simple name (which would cause JavaPoet
        // to treat it as a constructor). This should not happen with the _TransactionalProxy
        // suffix, but we guard against it.
        String proxySimpleName = targetCn.simpleName() + "_TransactionalProxy";
        if (methodName.equals(proxySimpleName)) {
            // Skip methods that would conflict with constructor name
            return null;
        }

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC);

        // Only add @Override if the method is not the proxy class name
        mb.addAnnotation(Override.class);
        mb.returns(returnType);

        // Parameters
        List<String> paramNames = new ArrayList<>();
        for (var param : method.getParameters()) {
            String paramName = param.getSimpleName().toString();
            paramNames.add(paramName);
            mb.addParameter(TypeName.get(param.asType()), paramName);
        }

        // Exceptions
        for (var ex : method.getThrownTypes()) {
            mb.addException(TypeName.get(ex));
        }

        // Method body: inline transaction boundary logic
        // TransactionDefinition def = TransactionDefinition.builder()
        //     .propagation(CONST.propagation())
        //     .isolation(CONST.isolation())
        //     .timeout(CONST.timeout())
        //     .readOnly(CONST.readOnly())
        //     .build();
        mb.addStatement("$T def = $T.builder()"
                + ".propagation($L.propagation())"
                + ".isolation($L.isolation())"
                + ".timeout($L.timeout())"
                + ".readOnly($L.readOnly())"
                + ".build()",
            ClassName.get("com.holo.framework.horm.core", "TransactionDefinition"),
            ClassName.get("com.holo.framework.horm.core", "TransactionDefinition"),
            constName, constName, constName, constName);

        // HormContext ctx = (HormContext) this.ctx;
        mb.addStatement("$T ctx = ($T) this.ctx",
            ClassName.get("com.holo.framework.horm.core", "HormContext"),
            ClassName.get("com.holo.framework.horm.core", "HormContext"));

        // String dataSourceName = DataSourceRegistry.DEFAULT_NAME;
        mb.addStatement("$T dataSourceName = $T.DEFAULT_NAME",
            String.class,
            ClassName.get("com.holo.framework.horm.core.datasource", "DataSourceRegistry"));

        // TransactionStatus status = TransactionManager.begin(ctx, dataSourceName, def);
        mb.addStatement("$T status = $T.begin(ctx, dataSourceName, def)",
            ClassName.get("com.holo.framework.horm.core", "TransactionStatus"),
            ClassName.get("com.holo.framework.horm.core", "TransactionManager"));

        // try { result = delegate.method(...); commit; return result; }
        // catch (Throwable ex) { rollback/commit based on rules; rethrow; }
        // finally { popAndResume; }
        mb.beginControlFlow("try");

        String delegateCall = buildDelegateCall(methodName, paramNames, returnType);
        if (returnType.equals(TypeName.VOID)) {
            mb.addStatement(delegateCall);
            mb.addStatement("$T.commit(status)",
                ClassName.get("com.holo.framework.horm.core", "TransactionManager"));
        } else {
            mb.addStatement("$T result = $L", returnType, delegateCall);
            mb.addStatement("$T.commit(status)",
                ClassName.get("com.holo.framework.horm.core", "TransactionManager"));
            mb.addStatement("return result");
        }

        mb.endControlFlow();
        mb.beginControlFlow("catch ($T ex)", Throwable.class);

        // if (meta.shouldRollback(ex)) { rollback } else { commit }
        mb.beginControlFlow("if ($L.shouldRollback(ex))", constName);
        mb.addStatement("$T.rollback(status)",
            ClassName.get("com.holo.framework.horm.core", "TransactionManager"));
        mb.endControlFlow();
        mb.beginControlFlow("else");
        mb.addStatement("$T.commit(status)",
            ClassName.get("com.holo.framework.horm.core", "TransactionManager"));
        mb.endControlFlow();

        mb.addStatement("rethrow(ex)");
        if (!returnType.equals(TypeName.VOID)) {
            mb.addStatement("return $L", defaultValue(returnType));
        }

        mb.endControlFlow();
        mb.beginControlFlow("finally");
        mb.addStatement("$T.popAndResume(dataSourceName, status)",
            ClassName.get("com.holo.framework.horm.core", "TransactionManager"));
        mb.endControlFlow();

        return mb.build();
    }

    private static String buildDelegateCall(String methodName, List<String> paramNames, TypeName returnType) {
        StringBuilder call = new StringBuilder("delegate.").append(methodName).append('(');
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) call.append(", ");
            call.append(paramNames.get(i));
        }
        call.append(')');
        return call.toString();
    }

    private static MethodSpec buildDelegateMethod(ExecutableElement method, ClassName targetCn) {
        String methodName = method.getSimpleName().toString();
        TypeName returnType = TypeName.get(method.getReturnType());

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(returnType);

        List<String> paramNames = new ArrayList<>();
        for (var param : method.getParameters()) {
            String paramName = param.getSimpleName().toString();
            paramNames.add(paramName);
            mb.addParameter(TypeName.get(param.asType()), paramName);
        }

        for (var ex : method.getThrownTypes()) {
            mb.addException(TypeName.get(ex));
        }

        StringBuilder call = new StringBuilder("delegate.").append(methodName).append('(');
        for (int i = 0; i < paramNames.size(); i++) {
            if (i > 0) call.append(", ");
            call.append(paramNames.get(i));
        }
        call.append(')');

        if (returnType.equals(TypeName.VOID)) {
            mb.addStatement(call.toString());
        } else {
            mb.addStatement("return " + call.toString());
        }

        return mb.build();
    }

    private static MethodSpec buildRethrowMethod() {
        return MethodSpec.methodBuilder("rethrow")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(void.class)
            .addParameter(Throwable.class, "ex")
            .beginControlFlow("if (ex instanceof $T r)", RuntimeException.class)
            .addStatement("throw r")
            .endControlFlow()
            .beginControlFlow("if (ex instanceof $T e)", Error.class)
            .addStatement("throw e")
            .endControlFlow()
            .addStatement("throw new $T(ex.getMessage(), ex)",
                ClassName.get("com.holo.framework.horm.core", "TransactionException"))
            .build();
    }

    private static TypeSpec buildFactoryClass(String factorySimpleName, ClassName targetCn,
                                               ClassName proxyCn, String generatedPackage) {
        ClassName factoryInterface = ClassName.get(TransactionProxyFactory.class);

        MethodSpec createMethod = MethodSpec.methodBuilder("create")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(Object.class)
            .addParameter(Object.class, "delegate")
            .addParameter(Object.class, "context")
            .addStatement("return new $T(($T) delegate, context)", proxyCn, targetCn)
            .build();

        MethodSpec targetTypeMethod = MethodSpec.methodBuilder("targetType")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)))
            .addStatement("return $T.class", targetCn)
            .build();

        return TypeSpec.classBuilder(factorySimpleName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(factoryInterface)
            .addMethod(createMethod)
            .addMethod(targetTypeMethod)
            .build();
    }

    // --- Utility methods ---

    private static AnnotationMirror findAnnotationMirror(Element element, String annotationQualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationQualifiedName)) {
                return mirror;
            }
        }
        return null;
    }

    private static String derivePackage(String qualifiedName, String simpleName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(0, dot) : "";
    }

    private static String metaConstName(String methodName) {
        // e.g. "createOrder" -> "CREATE_ORDER_META"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        sb.append("_META");
        return sb.toString();
    }

    private static String defaultValue(TypeName type) {
        if (type.equals(TypeName.BOOLEAN)) return "false";
        if (type.equals(TypeName.BYTE) || type.equals(TypeName.SHORT)
            || type.equals(TypeName.INT)) return "0";
        if (type.equals(TypeName.LONG)) return "0L";
        if (type.equals(TypeName.FLOAT)) return "0.0f";
        if (type.equals(TypeName.DOUBLE)) return "0.0d";
        if (type.equals(TypeName.CHAR)) return "'\\0'";
        return "null";
    }

    private static void writeJavaFile(String packageName, TypeSpec typeSpec, Filer filer) {
        try {
            JavaFile.builder(packageName, typeSpec)
                .indent("    ")
                .build()
                .writeTo(filer);
        } catch (IOException e) {
            // FilerException for duplicate writes is benign
        }
    }

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

    // --- Inner data class ---

    private static final class ProxyMethodInfo {
        final ExecutableElement method;
        final String methodName;
        final Propagation propagation;
        final Isolation isolation;
        final int timeout;
        final boolean readOnly;
        final List<String> rollbackFor;
        final List<String> noRollbackFor;

        ProxyMethodInfo(ExecutableElement method, AnnotationMirror txMirror,
                        ProcessingEnvironment processingEnv) {
            this.method = method;
            this.methodName = method.getSimpleName().toString();
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
}
