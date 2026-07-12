package com.holo.framework.horm.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * 在 {@code process-sources} 阶段扫描 {@code @Entity} 实体类，并自动插入 Active Record
 * 风格的静态方法签名。方法的具体实现由 HORM ByteBuddy 构建插件在 {@code process-classes}
 * 阶段填充。
 */
@Mojo(name = "generate-active-record", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class GenerateActiveRecordMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/java", required = true)
    private File mainSourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/test/java", required = true)
    private File testSourceDirectory;

    @Parameter(defaultValue = "Entity", required = true)
    private String entityAnnotationName;

    @Parameter(defaultValue = "com.holo.framework.horm.core.Model", required = true)
    private String modelClassName;

    @Parameter(defaultValue = "true")
    private boolean skipIfExists;

    private static final String INSTRUMENTED_MESSAGE =
            "Instrumented by HORM ByteBuddy plugin";

    @Override
    public void execute() throws MojoExecutionException {
        if (mainSourceDirectory.exists()) {
            processDirectory(mainSourceDirectory.toPath());
        }
        if (testSourceDirectory.exists()) {
            processDirectory(testSourceDirectory.toPath());
        }
    }

    private void processDirectory(Path root) throws MojoExecutionException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(this::processFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan source directory: " + root, e);
        }
    }

    private void processFile(Path file) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        JavaParser parser = new JavaParser(config);

        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(file.toFile());
        } catch (IOException e) {
            getLog().warn("Failed to parse " + file + ": " + e.getMessage());
            return;
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            getLog().warn("Failed to parse " + file + ": " + result.getProblems());
            return;
        }
        CompilationUnit cu = result.getResult().get();
        LexicalPreservingPrinter.setup(cu);

        boolean modified = false;
        for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (isEntity(type)) {
                modified |= generateHelpers(type, cu);
            }
        }

        if (modified) {
            try {
                Files.writeString(file, LexicalPreservingPrinter.print(cu));
                getLog().info("Generated Active Record helpers in " + file);
            } catch (IOException e) {
                getLog().warn("Failed to write " + file + ": " + e.getMessage());
            }
        }
    }

    private boolean isEntity(ClassOrInterfaceDeclaration type) {
        return type.isAnnotationPresent(entityAnnotationName)
                || type.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(entityAnnotationName));
    }

    private boolean generateHelpers(ClassOrInterfaceDeclaration type, CompilationUnit cu) {
        String entityName = type.getNameAsString();
        Map<String, MethodDescriptor> helpers = new LinkedHashMap<>();
        helpers.put("find", new MethodDescriptor(entityName,
                List.of(new Param("Object", "id")), Set.of()));
        helpers.put("findMany", new MethodDescriptor(
                "Map<Object, " + entityName + ">",
                List.of(new Param("Collection<?>", "ids")),
                Set.of("java.util.Map", "java.util.Collection")));
        helpers.put("all", new MethodDescriptor(
                "List<" + entityName + ">", List.of(), Set.of("java.util.List")));
        helpers.put("count", new MethodDescriptor("long", List.of(), Set.of()));
        helpers.put("query", new MethodDescriptor(
                "Query<" + entityName + ">", List.of(),
                Set.of("com.holo.framework.horm.core.query.Query")));
        helpers.put("update", new MethodDescriptor(
                "UpdateQuery<" + entityName + ">", List.of(),
                Set.of("com.holo.framework.horm.core.query.UpdateQuery")));

        boolean modified = false;
        for (Map.Entry<String, MethodDescriptor> entry : helpers.entrySet()) {
            modified |= addHelperIfMissing(type, cu, entry.getKey(), entry.getValue());
        }
        return modified;
    }

    private boolean addHelperIfMissing(ClassOrInterfaceDeclaration type,
                                       CompilationUnit cu,
                                       String name,
                                       MethodDescriptor descriptor) {
        if (skipIfExists && hasStaticMethod(type, name, descriptor.params)) {
            return false;
        }

        for (String importName : descriptor.imports) {
            cu.addImport(importName);
        }

        MethodDeclaration method = type.addMethod(name,
                Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);
        method.setType(StaticJavaParser.parseType(descriptor.returnType));
        for (Param param : descriptor.params) {
            method.addParameter(StaticJavaParser.parseType(param.type), param.name);
        }

        BlockStmt body = new BlockStmt();
        ObjectCreationExpr exception = new ObjectCreationExpr(
                null,
                StaticJavaParser.parseClassOrInterfaceType("UnsupportedOperationException"),
                new NodeList<>(new StringLiteralExpr(INSTRUMENTED_MESSAGE)));
        body.addStatement(new ThrowStmt(exception));
        method.setBody(body);

        return true;
    }

    private boolean hasStaticMethod(ClassOrInterfaceDeclaration type,
                                    String name,
                                    List<Param> params) {
        for (MethodDeclaration method : type.getMethodsByName(name)) {
            if (!method.isStatic()) {
                continue;
            }
            NodeList<com.github.javaparser.ast.body.Parameter> methodParams = method.getParameters();
            if (methodParams.size() != params.size()) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < params.size(); i++) {
                Type actual = methodParams.get(i).getType();
                Type expected = StaticJavaParser.parseType(params.get(i).type);
                if (!typeSimpleName(actual).equals(typeSimpleName(expected))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the simple (erased) name of a type, ignoring packages and generic
     * arguments. This makes comparison resilient to fully-qualified vs imported
     * type forms, which is enough for the small set of Active Record helpers.
     */
    private static String typeSimpleName(Type type) {
        if (type.isClassOrInterfaceType()) {
            return type.asClassOrInterfaceType().getNameAsString();
        }
        if (type.isPrimitiveType()) {
            return type.asPrimitiveType().asString();
        }
        if (type.isArrayType()) {
            return typeSimpleName(type.asArrayType().getComponentType()) + "[]";
        }
        if (type.isWildcardType()) {
            return "?";
        }
        return type.toString();
    }

    private record Param(String type, String name) {
    }

    private record MethodDescriptor(String returnType, List<Param> params, Set<String> imports) {
    }
}
