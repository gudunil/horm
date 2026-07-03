package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GeneratedValue;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.Table;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import java.util.Set;

/**
 * Parses a {@code @Entity}-annotated {@link TypeElement} into an
 * {@link EntityDescriptor} intermediate representation that the JavaPoet
 * builders (M1-4) can consume directly.
 *
 * <p>The parser is intentionally permissive: it does not report compile-time
 * errors for missing {@code @Id} or unsupported field types — that is the job
 * of {@code EntityValidator} in M1-5. This keeps the parsing pipeline robust
 * enough to keep compiling sibling entities when one entity is malformed.
 *
 * <h3>Resolution rules (documented in the M1-3 plan)</h3>
 * <ul>
 *   <li>Table name: {@code @Table.name} &gt; {@code @Entity.table} &gt; class simple name in snake_case</li>
 *   <li>Schema: {@code @Table.schema} &gt; {@code @Entity.schema}</li>
 *   <li>Column name: {@code @Column.name} &gt; field name in snake_case</li>
 *   <li>Primary-key strategy: {@code @GeneratedValue} (if present) &gt; {@code @Id.strategy}</li>
 *   <li>Getter: {@code isXxx} for boolean/{@code Boolean}, {@code getXxx} otherwise</li>
 *   <li>Setter: {@code setXxx} unconditionally</li>
 * </ul>
 *
 * <p>Field scan scope: only directly declared fields of the type (no superclass
 * recursion). {@code static} and {@code transient} fields are skipped. Fields
 * without {@code @Id} or {@code @Column} are skipped (M1 does not parse
 * relation annotations — that is M3).
 */
public final class EntityDescriptorParser {

    private EntityDescriptorParser() {
    }

    /**
     * Parse a single {@code @Entity} type into a descriptor.
     *
     * @param type the entity type element (must be {@link ElementKind#CLASS})
     * @param env  the processing environment (for {@code Elements} utility)
     * @return a populated descriptor; {@code idField()} may be {@code null} if no {@code @Id} is present
     */
    public static EntityDescriptor parse(TypeElement type, ProcessingEnvironment env) {
        String packageName = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String qualifiedName = type.getQualifiedName().toString();

        Entity entityAnno = type.getAnnotation(Entity.class);
        Table tableAnno = type.getAnnotation(Table.class);

        String tableName = firstNonEmpty(
            tableAnno != null ? tableAnno.name() : "",
            entityAnno != null ? entityAnno.table() : "",
            snake(simpleName)
        );
        String schema = firstNonEmpty(
            tableAnno != null ? tableAnno.schema() : "",
            entityAnno != null ? entityAnno.schema() : ""
        );
        String dataSource = entityAnno != null ? entityAnno.dataSource() : "";

        EntityDescriptor.Builder builder = EntityDescriptor.builder()
            .packageName(packageName)
            .simpleName(simpleName)
            .qualifiedName(qualifiedName)
            .tableName(tableName)
            .schema(schema)
            .dataSource(dataSource);

        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            Set<Modifier> mods = field.getModifiers();
            if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.TRANSIENT)) {
                continue;
            }

            Id idAnno = field.getAnnotation(Id.class);
            Column colAnno = field.getAnnotation(Column.class);
            if (idAnno == null && colAnno == null) {
                continue;
            }

            EntityDescriptor.FieldDescriptor fd = parseField(field, idAnno, colAnno);
            builder.addField(fd);
            if (idAnno != null) {
                builder.idField(fd);
            }
        }

        return builder.build();
    }

    private static EntityDescriptor.FieldDescriptor parseField(VariableElement field,
                                              Id idAnno,
                                              Column colAnno) {
        String name = field.getSimpleName().toString();
        String column = (colAnno != null && !colAnno.name().isEmpty())
            ? colAnno.name()
            : snake(name);

        TypeMirror typeMirror = field.asType();
        TypeKind kind = typeMirror.getKind();
        boolean primitive = kind.isPrimitive();
        String typeQualifiedName = typeMirror.toString();
        String typeName = simpleTypeName(typeMirror);
        boolean enumType = isEnumType(typeMirror);
        String enumQualifiedName = enumType ? typeQualifiedName : null;

        GenerationType strategy = null;
        if (idAnno != null) {
            GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
            strategy = gv != null ? gv.strategy() : idAnno.strategy();
        }

        boolean nullable = (colAnno == null) || colAnno.nullable();
        boolean unique = (colAnno != null) && colAnno.unique();
        int length = (colAnno == null) ? 255 : colAnno.length();
        int precision = (colAnno == null) ? 0 : colAnno.precision();
        int scale = (colAnno == null) ? 0 : colAnno.scale();
        boolean insertable = (colAnno == null) || colAnno.insertable();
        boolean updatable = (colAnno == null) || colAnno.updatable();

        boolean isBoolean = (kind == TypeKind.BOOLEAN)
            || "java.lang.Boolean".equals(typeQualifiedName);
        String getterName = (isBoolean ? "is" : "get") + capitalize(name);
        String setterName = "set" + capitalize(name);

        return new EntityDescriptor.FieldDescriptor(
            name,
            column,
            typeName,
            typeQualifiedName,
            primitive,
            idAnno != null,
            strategy,
            nullable,
            unique,
            length,
            precision,
            scale,
            insertable,
            updatable,
            getterName,
            setterName,
            enumType,
            enumQualifiedName
        );
    }

    /**
     * Convert a camelCase identifier to snake_case.
     * Example: {@code createdAt} → {@code created_at}, {@code userId} → {@code user_id}.
     * Consecutive capitals (e.g. {@code URL}) become {@code u_r_l}; this is acceptable
     * for ORM column naming where such identifiers are rare.
     */
    static String snake(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * Extract a simple type name from a {@link TypeMirror}.
     * Examples: {@code java.lang.Long} → {@code Long}; {@code long} → {@code long};
     * {@code byte[]} → {@code byte[]}; {@code java.util.List<String>} → {@code List}.
     */
    private static String simpleTypeName(TypeMirror typeMirror) {
        String s = typeMirror.toString();
        int idx = s.lastIndexOf('.');
        String tail = (idx >= 0) ? s.substring(idx + 1) : s;
        int generic = tail.indexOf('<');
        if (generic >= 0) {
            tail = tail.substring(0, generic);
        }
        return tail;
    }

    private static boolean isEnumType(TypeMirror typeMirror) {
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return false;
        }
        Element element = ((DeclaredType) typeMirror).asElement();
        return element != null && element.getKind() == ElementKind.ENUM;
    }
}
