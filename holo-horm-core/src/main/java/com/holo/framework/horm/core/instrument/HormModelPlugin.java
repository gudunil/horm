package com.holo.framework.horm.core.instrument;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.query.Query;
import com.holo.framework.horm.core.query.UpdateQuery;
import com.holo.framework.horm.meta.annotation.Entity;

import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy build-time plugin that injects type-safe Active Record static
 * helpers directly into the bytecode of every class annotated with
 * {@link Entity}.
 *
 * <p>The Java source files are left untouched. For example, after
 * transformation the bytecode of {@code User} contains:
 * <pre>
 * public static User find(Object id) {
 *     return Model.find(User.class, id);
 * }
 * </pre>
 * This allows the Active Record style {@code User.find(id)} to compile and
 * execute with full type safety without requiring reflection at runtime.
 */
public class HormModelPlugin implements Plugin {

    private static final TypeDescription MODEL_TYPE =
            TypeDescription.ForLoadedType.of(Model.class);

    private static final TypeDescription ENTITY_TYPE =
            TypeDescription.ForLoadedType.of(Entity.class);

    @Override
    public boolean matches(TypeDescription target) {
        if (!target.isAssignableTo(MODEL_TYPE)) {
            return false;
        }
        return target.getDeclaredAnnotations().isAnnotationPresent(ENTITY_TYPE);
    }

    @Override
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder,
                                        TypeDescription typeDescription,
                                        ClassFileLocator classFileLocator) {
        DynamicType.Builder<?> result = builder;
        result = instrument(result, typeDescription, "find", Object.class);
        result = instrument(result, typeDescription, "findMany", Collection.class);
        result = instrument(result, typeDescription, "all");
        result = instrument(result, typeDescription, "count");
        result = instrument(result, typeDescription, "query");
        result = instrument(result, typeDescription, "update");
        return result;
    }

    @Override
    public void close() throws IOException {
        // No resources to release.
    }

    private static DynamicType.Builder<?> instrument(DynamicType.Builder<?> builder,
                                                     TypeDescription entityType,
                                                     String helperName,
                                                     Class<?>... helperArgTypes) {
        Class<?>[] modelArgTypes = new Class<?>[helperArgTypes.length + 1];
        modelArgTypes[0] = Class.class;
        System.arraycopy(helperArgTypes, 0, modelArgTypes, 1, helperArgTypes.length);

        Method modelMethod;
        try {
            modelMethod = Model.class.getDeclaredMethod(helperName, modelArgTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Cannot locate Model." + helperName + " helper", e);
        }

        MethodCall call = MethodCall.invoke(modelMethod).with(entityType);
        for (int i = 0; i < helperArgTypes.length; i++) {
            call = call.withArgument(i);
        }

        Implementation implementation = call
                .withAssigner(Assigner.DEFAULT, Assigner.Typing.DYNAMIC);

        TypeDescription.Generic returnType = resolveReturnType(entityType, helperName);

        DynamicType.Builder.MethodDefinition.ParameterDefinition<?> def = builder
                .defineMethod(helperName, returnType, Visibility.PUBLIC, Ownership.STATIC);
        for (int i = 0; i < helperArgTypes.length; i++) {
            def = def.withParameter(helperArgTypes[i], "arg" + i);
        }
        return def.intercept(implementation);
    }

    private static TypeDescription.Generic resolveReturnType(TypeDescription entityType,
                                                             String helperName) {
        return switch (helperName) {
            case "find" -> TypeDescription.Generic.Builder
                    .parameterizedType(entityType.asErasure(), new TypeDescription[0])
                    .build();
            case "findMany" -> {
                TypeDescription map = TypeDescription.ForLoadedType.of(Map.class);
                TypeDescription object = TypeDescription.ForLoadedType.of(Object.class);
                yield TypeDescription.Generic.Builder
                        .parameterizedType(map, object, entityType.asErasure())
                        .build();
            }
            case "all" -> {
                TypeDescription list = TypeDescription.ForLoadedType.of(List.class);
                yield TypeDescription.Generic.Builder
                        .parameterizedType(list, entityType.asErasure())
                        .build();
            }
            case "count" -> TypeDescription.Generic.Builder.rawType(long.class).build();
            case "query" -> {
                TypeDescription query = TypeDescription.ForLoadedType.of(Query.class);
                yield TypeDescription.Generic.Builder
                        .parameterizedType(query, entityType.asErasure())
                        .build();
            }
            case "update" -> {
                TypeDescription updateQuery = TypeDescription.ForLoadedType.of(UpdateQuery.class);
                yield TypeDescription.Generic.Builder
                        .parameterizedType(updateQuery, entityType.asErasure())
                        .build();
            }
            default -> throw new IllegalArgumentException(
                    "Unknown Active Record helper: " + helperName);
        };
    }
}
