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
import net.bytebuddy.matcher.ElementMatchers;

/**
 * ByteBuddy build-time plugin that defines type-safe Active Record static
 * helpers directly in the bytecode of every class annotated with
 * {@link Entity}. The source {@code .java} files are left untouched.
 *
 * <p>For example, after transformation the bytecode of {@code User} contains:
 * <pre>
 * public static User find(Object id) {
 *     return Model.find(User.class, id);
 * }
 * </pre>
 *
 * <p>If the helper already exists in the class (e.g. a manually written
 * overload), it is left unchanged.
 */
public class HormModelPlugin implements Plugin {

    private static final TypeDescription MODEL_TYPE =
            TypeDescription.ForLoadedType.of(Model.class);

    private static final TypeDescription ENTITY_TYPE =
            TypeDescription.ForLoadedType.of(Entity.class);

    private static final TypeDescription OBJECT_TYPE =
            TypeDescription.ForLoadedType.of(Object.class);

    private static final TypeDescription LONG_TYPE =
            TypeDescription.ForLoadedType.of(long.class);

    private static final TypeDescription COLLECTION_TYPE =
            TypeDescription.ForLoadedType.of(Collection.class);

    private static final TypeDescription MAP_TYPE =
            TypeDescription.ForLoadedType.of(Map.class);

    private static final TypeDescription LIST_TYPE =
            TypeDescription.ForLoadedType.of(List.class);

    private static final TypeDescription QUERY_TYPE =
            TypeDescription.ForLoadedType.of(Query.class);

    private static final TypeDescription UPDATE_QUERY_TYPE =
            TypeDescription.ForLoadedType.of(UpdateQuery.class);

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
        result = defineFind(result, typeDescription);
        result = defineFindMany(result, typeDescription);
        result = defineAll(result, typeDescription);
        result = defineCount(result, typeDescription);
        result = defineQuery(result, typeDescription);
        result = defineUpdate(result, typeDescription);
        return result;
    }

    @Override
    public void close() throws IOException {
        // No resources to release.
    }

    private static DynamicType.Builder<?> defineFind(DynamicType.Builder<?> builder,
                                                     TypeDescription entityType) {
        return defineIfMissing(builder, entityType, "find",
                entityType.asGenericType(),
                new Parameter(OBJECT_TYPE.asGenericType(), "id"),
                Model.class, "find", Class.class, Object.class);
    }

    private static DynamicType.Builder<?> defineFindMany(DynamicType.Builder<?> builder,
                                                         TypeDescription entityType) {
        TypeDescription.Generic returnType = TypeDescription.Generic.Builder
                .parameterizedType(MAP_TYPE, OBJECT_TYPE.asGenericType(), entityType.asGenericType())
                .build();
        TypeDescription.Generic paramType = COLLECTION_TYPE.asGenericType();
        return defineIfMissing(builder, entityType, "findMany",
                returnType, new Parameter(paramType, "ids"),
                Model.class, "findMany", Class.class, Collection.class);
    }

    private static DynamicType.Builder<?> defineAll(DynamicType.Builder<?> builder,
                                                    TypeDescription entityType) {
        TypeDescription.Generic returnType = TypeDescription.Generic.Builder
                .parameterizedType(LIST_TYPE, entityType.asGenericType())
                .build();
        return defineIfMissing(builder, entityType, "all",
                returnType, Model.class, "all", Class.class);
    }

    private static DynamicType.Builder<?> defineCount(DynamicType.Builder<?> builder,
                                                      TypeDescription entityType) {
        return defineIfMissing(builder, entityType, "count",
                LONG_TYPE.asGenericType(), Model.class, "count", Class.class);
    }

    private static DynamicType.Builder<?> defineQuery(DynamicType.Builder<?> builder,
                                                      TypeDescription entityType) {
        TypeDescription.Generic returnType = TypeDescription.Generic.Builder
                .parameterizedType(QUERY_TYPE, entityType.asGenericType())
                .build();
        return defineIfMissing(builder, entityType, "query",
                returnType, Model.class, "query", Class.class);
    }

    private static DynamicType.Builder<?> defineUpdate(DynamicType.Builder<?> builder,
                                                       TypeDescription entityType) {
        TypeDescription.Generic returnType = TypeDescription.Generic.Builder
                .parameterizedType(UPDATE_QUERY_TYPE, entityType.asGenericType())
                .build();
        return defineIfMissing(builder, entityType, "update",
                returnType, Model.class, "update", Class.class);
    }

    private static DynamicType.Builder<?> defineIfMissing(DynamicType.Builder<?> builder,
                                                          TypeDescription entityType,
                                                          String name,
                                                          TypeDescription.Generic returnType,
                                                          Class<?> modelClass,
                                                          String modelMethodName,
                                                          Class<?>... modelMethodParamTypes) {
        return defineIfMissing(builder, entityType, name, returnType,
                new Parameter[0], modelClass, modelMethodName, modelMethodParamTypes);
    }

    private static DynamicType.Builder<?> defineIfMissing(DynamicType.Builder<?> builder,
                                                          TypeDescription entityType,
                                                          String name,
                                                          TypeDescription.Generic returnType,
                                                          Parameter parameter,
                                                          Class<?> modelClass,
                                                          String modelMethodName,
                                                          Class<?>... modelMethodParamTypes) {
        return defineIfMissing(builder, entityType, name, returnType,
                new Parameter[]{parameter}, modelClass, modelMethodName, modelMethodParamTypes);
    }

    private static DynamicType.Builder<?> defineIfMissing(DynamicType.Builder<?> builder,
                                                          TypeDescription entityType,
                                                          String name,
                                                          TypeDescription.Generic returnType,
                                                          Parameter[] parameters,
                                                          Class<?> modelClass,
                                                          String modelMethodName,
                                                          Class<?>... modelMethodParamTypes) {
        if (hasStaticMethod(entityType, name, parameters.length)) {
            return builder;
        }

        Method modelMethod;
        try {
            modelMethod = modelClass.getDeclaredMethod(modelMethodName, modelMethodParamTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Cannot locate Model." + modelMethodName + " helper", e);
        }

        MethodCall call = MethodCall.invoke(modelMethod).with(entityType);
        for (int i = 0; i < parameters.length; i++) {
            call = call.withArgument(i);
        }
        Implementation implementation = call
                .withAssigner(Assigner.DEFAULT, Assigner.Typing.DYNAMIC);

        DynamicType.Builder.MethodDefinition.ParameterDefinition<?> definition = builder
                .defineMethod(name, returnType, Visibility.PUBLIC, Ownership.STATIC);
        for (Parameter parameter : parameters) {
            definition = definition.withParameter(parameter.type, parameter.name);
        }
        return definition.intercept(implementation);
    }

    private static boolean hasStaticMethod(TypeDescription type,
                                           String name,
                                           int parameterCount) {
        return !type.getDeclaredMethods()
                .filter(ElementMatchers.named(name)
                        .and(ElementMatchers.isStatic())
                        .and(ElementMatchers.takesArguments(parameterCount)))
                .isEmpty();
    }

    private static final class Parameter {
        final TypeDescription.Generic type;
        final String name;

        Parameter(TypeDescription.Generic type, String name) {
            this.type = type;
            this.name = name;
        }
    }
}
