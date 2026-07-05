package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.RelationType;
import com.holo.framework.horm.meta.annotation.BelongsTo;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.HasAndBelongsToMany;
import com.holo.framework.horm.meta.annotation.HasMany;
import com.holo.framework.horm.meta.annotation.HasManyThrough;
import com.holo.framework.horm.meta.annotation.HasOne;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.Version;
import com.squareup.javapoet.TypeName;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time validator for {@code @Entity}-annotated types.
 *
 * <p>Runs immediately after {@link EntityDescriptorParser#parse} has built the
 * {@link EntityDescriptor} IR and before the JavaPoet builders emit companion
 * classes. Each violated rule is reported via
 * {@link javax.annotation.processing.Messager#printMessage} as a
 * {@link Diagnostic.Kind#ERROR} on the offending element — exceptions are never
 * thrown so that sibling entities in the same round remain processable
 * (compile failures are surfaced by {@code javac} based on the diagnostics).
 *
 * <p>Rules (executed independently in order R1 → R2 → R3 → R4, no short-circuit):
 * <ul>
 *   <li><b>R1</b> — must declare exactly one {@code @Id} field</li>
 *   <li><b>R2</b> — must extend {@code com.holo.framework.horm.core.Model<T>}</li>
 *   <li><b>R3</b> — {@code @Id}/{@code @Column} fields must not be {@code final}
 *       ({@code Model<T>} requires setters)</li>
 *   <li><b>R4</b> — field types must be supported by {@link TypeMapper}</li>
 * </ul>
 *
 * <p>R2 compares fully-qualified type names as strings only — no class loading.
 * This lets the rule run even before the {@code Model<T>} base class exists in
 * the {@code holo-horm-core} module (M1-6).
 */
public final class EntityValidator {

    private static final String MODEL_FQN = "com.holo.framework.horm.core.Model";

    private EntityValidator() {
    }

    /**
     * Validate a parsed entity descriptor against the HORM contract.
     *
     * <p>Reports {@link Diagnostic.Kind#ERROR} diagnostics for each violated
     * rule. Never throws — callers may continue invoking builders after this
     * method returns (builders tolerate incomplete descriptors, e.g. a missing
     * {@code idField}, and any builder exception is caught by the processor's
     * outer try-catch).
     *
     * @param d    the descriptor produced by {@link EntityDescriptorParser#parse}
     * @param type the {@code @Entity}-annotated type element
     * @param env  the processing environment (for the {@link javax.annotation.processing.Messager})
     */
    public static void validate(EntityDescriptor d, TypeElement type, ProcessingEnvironment env) {
        // R1: must have exactly one @Id field
        if (d.idField() == null) {
            env.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "@Entity must declare exactly one @Id field",
                type
            );
        }

        // R2: must extend Model<T>
        if (!extendsModel(type)) {
            env.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "@Entity " + d.qualifiedName() + " must extend Model<T>",
                type
            );
        }

        // Index field elements by simple name so R4 can report on the exact element
        Map<String, VariableElement> fieldElements = new HashMap<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                fieldElements.put(enclosed.getSimpleName().toString(), (VariableElement) enclosed);
            }
        }

        // R3: @Id/@Column fields must not be final (Model<T> requires setters)
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            Set<Modifier> mods = enclosed.getModifiers();
            if (!mods.contains(Modifier.FINAL)) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            if (field.getAnnotation(Id.class) != null || field.getAnnotation(Column.class) != null) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "field '" + enclosed.getSimpleName() + "' must not be final; Model<T> requires setter",
                    enclosed
                );
            }
        }

        // R4: field types must be supported by TypeMapper
        for (EntityDescriptor.FieldDescriptor fd : d.fields()) {
            if (!isTypeSupported(fd)) {
                VariableElement field = fieldElements.get(fd.name());
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "unsupported field type '" + fd.typeQualifiedName() + "' on field '" + fd.name() + "'",
                    field != null ? field : type
                );
            }
        }

        // R5-R9: relation field validation (M3)
        validateRelations(d, type, env);

        // R10-R11: @Version field validation (M4)
        validateVersion(d, type, env, fieldElements);
    }

    /**
     * Validate relation fields (R5-R9).
     *
     * <p>Rules:
     * <ul>
     *   <li><b>R5</b> — relation field type must be {@code java.util.List}</li>
     *   <li><b>R6</b> — relation target entity must be {@code @Entity}-annotated</li>
     *   <li><b>R7</b> — {@code @HasAndBelongsToMany} must declare non-empty {@code joinTable}</li>
     *   <li><b>R8</b> — {@code @HasManyThrough} must declare a {@code through} entity</li>
     *   <li><b>R9</b> — relation fields must not be {@code final}</li>
     * </ul>
     */
    private static void validateRelations(EntityDescriptor d, TypeElement type, ProcessingEnvironment env) {
        // R5 + R9: scan relation fields directly from the type element
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            boolean isRelationField = field.getAnnotation(BelongsTo.class) != null
                || field.getAnnotation(HasOne.class) != null
                || field.getAnnotation(HasMany.class) != null
                || field.getAnnotation(HasAndBelongsToMany.class) != null
                || field.getAnnotation(HasManyThrough.class) != null;
            if (!isRelationField) {
                continue;
            }

            // R9: relation fields must not be final
            if (field.getModifiers().contains(Modifier.FINAL)) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "relation field '" + field.getSimpleName() + "' must not be final; Model<T> requires setter",
                    field
                );
            }

            // R5: relation field type must be java.util.List
            String typeFqn = field.asType().toString();
            if (!typeFqn.startsWith("java.util.List<")) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "relation field '" + field.getSimpleName() + "' must be of type java.util.List",
                    field
                );
            }
        }

        // R6 + R7 + R8: validate each parsed relation descriptor
        for (EntityDescriptor.RelationDescriptor rd : d.relations()) {
            VariableElement field = findField(type, rd.name());

            // R6: relation target must be @Entity-annotated
            TypeElement targetElement = env.getElementUtils().getTypeElement(rd.targetEntityQualifiedName());
            if (targetElement == null || targetElement.getAnnotation(Entity.class) == null) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "relation '" + rd.name() + "' target '" + rd.targetEntityQualifiedName()
                        + "' is not an @Entity",
                    field != null ? field : type
                );
            }

            // R7: @HasAndBelongsToMany must declare non-empty joinTable
            if (rd.type() == RelationType.HAS_AND_BELONGS_TO_MANY
                && (rd.joinTable() == null || rd.joinTable().isEmpty())) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "relation '" + rd.name() + "' (@HasAndBelongsToMany) must declare a non-empty joinTable",
                    field != null ? field : type
                );
            }

            // R8: @HasManyThrough must declare a through entity
            if (rd.type() == RelationType.HAS_MANY_THROUGH
                && rd.throughQualifiedName() == null) {
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "relation '" + rd.name() + "' (@HasManyThrough) must declare a through entity",
                    field != null ? field : type
                );
            }
        }
    }

    /** Find a field element by simple name on the given type. */
    private static VariableElement findField(TypeElement type, String name) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                && enclosed.getSimpleName().toString().equals(name)) {
                return (VariableElement) enclosed;
            }
        }
        return null;
    }

    /**
     * Walk the superclass chain looking for {@code com.holo.framework.horm.core.Model}.
     * String-based comparison only — no class loading, so this works even before
     * the core module exists (M1-6). The chain terminates when the superclass is
     * not a declared type (e.g. {@code java.lang.Object}'s superclass is
     * {@link TypeKind#NONE}).
     */
    private static boolean extendsModel(TypeElement type) {
        TypeMirror sup = type.getSuperclass();
        while (sup != null && sup.getKind() == TypeKind.DECLARED) {
            Element supElem = ((DeclaredType) sup).asElement();
            if (!(supElem instanceof TypeElement)) {
                break;
            }
            TypeElement supType = (TypeElement) supElem;
            if (MODEL_FQN.equals(supType.getQualifiedName().toString())) {
                return true;
            }
            sup = supType.getSuperclass();
        }
        return false;
    }

    /**
     * Determine whether a field type is supported by the HORM type-mapping table.
     *
     * <p>The task contract specifies probing via {@link TypeMapper#boxType},
     * which returns {@code null} for unsupported types. In the current M1-4
     * implementation {@code boxType} falls back to {@code ClassName.bestGuess}
     * and never returns {@code null}, so {@link TypeMapper#typedFieldClass} —
     * which does return {@code null} for types without a {@code TypedField}
     * subclass — is used as the authoritative probe. {@code byte[]} is treated
     * as supported because {@code Row} provides {@code getBytes}/{@code setBytes}
     * even though there is no {@code TypedField} subclass for it.
     */
    private static boolean isTypeSupported(EntityDescriptor.FieldDescriptor fd) {
        // Honour the documented contract: if boxType ever returns null the type
        // is definitely unsupported. Currently it never does, but this keeps the
        // check forward-compatible.
        TypeName boxed = TypeMapper.boxType(fd);
        if (boxed == null) {
            return false;
        }
        if (TypeMapper.typedFieldClass(fd) != null) {
            return true;
        }
        // byte[] has no TypedField subclass but is supported via Row.getBytes.
        return "byte[]".equals(fd.typeQualifiedName());
    }

    /**
     * Validate @Version fields (R10-R11).
     *
     * <p>Rules:
     * <ul>
     *   <li><b>R10</b> — @Version field type must be int/Integer/long/Long</li>
     *   <li><b>R11</b> — at most one @Version field per entity</li>
     * </ul>
     */
    private static void validateVersion(EntityDescriptor d, TypeElement type,
                                        ProcessingEnvironment env,
                                        Map<String, VariableElement> fieldElements) {
        int versionCount = 0;
        Set<String> allowedTypes = Set.of(
            "int", "java.lang.Integer", "long", "java.lang.Long"
        );
        for (EntityDescriptor.FieldDescriptor fd : d.fields()) {
            if (!fd.version()) continue;
            versionCount++;
            // R10: type must be int/Integer/long/Long
            if (!allowedTypes.contains(fd.typeQualifiedName())) {
                VariableElement field = fieldElements.get(fd.name());
                env.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Version field '" + fd.name() + "' must be of type int, Integer, long, or Long",
                    field != null ? field : type
                );
            }
        }
        // R11: at most one @Version field
        if (versionCount > 1) {
            env.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "@Entity " + d.qualifiedName() + " must have at most one @Version field",
                type
            );
        }
    }
}
