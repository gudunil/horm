package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.RelationType;
import com.holo.framework.horm.meta.annotation.BelongsTo;
import com.holo.framework.horm.meta.annotation.CascadeType;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GeneratedValue;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.HasAndBelongsToMany;
import com.holo.framework.horm.meta.annotation.HasMany;
import com.holo.framework.horm.meta.annotation.HasManyThrough;
import com.holo.framework.horm.meta.annotation.HasOne;
import com.holo.framework.horm.meta.annotation.Id;
import com.holo.framework.horm.meta.annotation.Table;
import com.holo.framework.horm.meta.annotation.Version;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import java.util.Set;
import java.util.function.Supplier;

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
 * without {@code @Id}, {@code @Column}, or one of the M3 relation annotations
 * ({@code @BelongsTo}/{@code @HasOne}/{@code @HasMany}/
 * {@code @HasAndBelongsToMany}/{@code @HasManyThrough}) are skipped.
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
            Version versionAnno = field.getAnnotation(Version.class);
            if (idAnno == null && colAnno == null && versionAnno == null) {
                // M3: probe for relation annotations (@BelongsTo/@HasOne/@HasMany/
                // @HasAndBelongsToMany/@HasManyThrough). A field with only a relation
                // annotation is a relation field, not a column.
                EntityDescriptor.RelationDescriptor rd = parseRelation(field, simpleName);
                if (rd != null) {
                    builder.addRelation(rd);
                }
                continue;
            }

            EntityDescriptor.FieldDescriptor fd = parseField(field, idAnno, colAnno, versionAnno);
            builder.addField(fd);
            if (idAnno != null) {
                builder.idField(fd);
            }
        }

        return builder.build();
    }

    private static EntityDescriptor.FieldDescriptor parseField(VariableElement field,
                                              Id idAnno,
                                              Column colAnno,
                                              Version versionAnno) {
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

        boolean isVersionField = versionAnno != null;
        // Version fields are managed by the framework: not insertable, not updatable by user
        boolean effectiveInsertable = isVersionField ? false : ((colAnno == null) || colAnno.insertable());
        boolean effectiveUpdatable = isVersionField ? false : ((colAnno == null) || colAnno.updatable());

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
            effectiveInsertable,
            effectiveUpdatable,
            getterName,
            setterName,
            enumType,
            enumQualifiedName,
            isVersionField
        );
    }

    /**
     * Probe the field for one of the five relation annotations and build a
     * {@link EntityDescriptor.RelationDescriptor} if present. Returns
     * {@code null} when the field has no relation annotation.
     *
     * <p>Default foreign-key derivation:
     * <ul>
     *   <li>{@code @BelongsTo} → {@code <targetSimpleLower>_id} (FK on this entity)</li>
     *   <li>{@code @HasOne}/{@code @HasMany} → {@code <ownerSimpleLower>_id} (FK on target)</li>
     *   <li>{@code @HasAndBelongsToMany} → {@code <ownerSimpleLower>_id} and {@code <targetSimpleLower>_id}</li>
     *   <li>{@code @HasManyThrough} → {@code <ownerSimpleLower>_id} and {@code <targetSimpleLower>_id}</li>
     * </ul>
     *
     * <p>{@code Class<?>} attributes are extracted via {@link MirroredTypeException}
     * because the {@code Class} cannot be loaded during annotation processing.
     */
    static EntityDescriptor.RelationDescriptor parseRelation(VariableElement field, String ownerSimpleName) {
        BelongsTo belongsTo = field.getAnnotation(BelongsTo.class);
        if (belongsTo != null) {
            String targetFqn = mirrorToFqn(belongsTo::targetEntity);
            String targetSimple = simpleName(targetFqn);
            String fk = belongsTo.foreignKey().isEmpty()
                ? snake(targetSimple) + "_id"
                : belongsTo.foreignKey();
            return buildRelation(field, RelationType.BELONGS_TO, targetFqn, targetSimple,
                fk, null, null, null, null, belongsTo.cascade());
        }

        HasOne hasOne = field.getAnnotation(HasOne.class);
        if (hasOne != null) {
            String targetFqn = mirrorToFqn(hasOne::targetEntity);
            String targetSimple = simpleName(targetFqn);
            String fk = hasOne.foreignKey().isEmpty()
                ? snake(ownerSimpleName) + "_id"
                : hasOne.foreignKey();
            return buildRelation(field, RelationType.HAS_ONE, targetFqn, targetSimple,
                fk, null, null, null, null, hasOne.cascade());
        }

        HasMany hasMany = field.getAnnotation(HasMany.class);
        if (hasMany != null) {
            String targetFqn = mirrorToFqn(hasMany::targetEntity);
            String targetSimple = simpleName(targetFqn);
            String fk = hasMany.foreignKey().isEmpty()
                ? snake(ownerSimpleName) + "_id"
                : hasMany.foreignKey();
            return buildRelation(field, RelationType.HAS_MANY, targetFqn, targetSimple,
                fk, null, null, null, null, hasMany.cascade());
        }

        HasAndBelongsToMany habtm = field.getAnnotation(HasAndBelongsToMany.class);
        if (habtm != null) {
            String targetFqn = mirrorToFqn(habtm::targetEntity);
            String targetSimple = simpleName(targetFqn);
            String fk = habtm.foreignKey().isEmpty()
                ? snake(ownerSimpleName) + "_id"
                : habtm.foreignKey();
            String afk = habtm.associationForeignKey().isEmpty()
                ? snake(targetSimple) + "_id"
                : habtm.associationForeignKey();
            String jt = habtm.joinTable();
            return buildRelation(field, RelationType.HAS_AND_BELONGS_TO_MANY,
                targetFqn, targetSimple, fk, afk, jt.isEmpty() ? null : jt, null, null, habtm.cascade());
        }

        HasManyThrough hmt = field.getAnnotation(HasManyThrough.class);
        if (hmt != null) {
            String targetFqn = mirrorToFqn(hmt::targetEntity);
            String targetSimple = simpleName(targetFqn);
            String throughFqn = mirrorToFqn(hmt::through);
            boolean hasThrough = !"void".equals(throughFqn);
            String fk = hmt.foreignKey().isEmpty()
                ? snake(ownerSimpleName) + "_id"
                : hmt.foreignKey();
            String afk = hmt.associationForeignKey().isEmpty()
                ? snake(targetSimple) + "_id"
                : hmt.associationForeignKey();
            return buildRelation(field, RelationType.HAS_MANY_THROUGH, targetFqn, targetSimple,
                fk, afk, null,
                hasThrough ? throughFqn : null,
                hasThrough ? simpleName(throughFqn) : null,
                hmt.cascade());
        }

        return null;
    }

    private static EntityDescriptor.RelationDescriptor buildRelation(VariableElement field,
                                                     RelationType type,
                                                     String targetFqn,
                                                     String targetSimple,
                                                     String fk,
                                                     String afk,
                                                     String jt,
                                                     String throughFqn,
                                                     String throughSimple,
                                                     CascadeType[] cascadeTypes) {
        String name = field.getSimpleName().toString();
        String getterName = "get" + capitalize(name);
        String setterName = "set" + capitalize(name);
        return new EntityDescriptor.RelationDescriptor(name, targetFqn, targetSimple, type,
            fk, afk, jt, throughFqn, throughSimple, getterName, setterName, cascadeTypes);
    }

    /**
     * Extract the fully-qualified name from a {@code Class<?>} annotation attribute
     * via {@link MirroredTypeException}. During annotation processing the {@code Class}
     * cannot be loaded, so accessing it throws {@link MirroredTypeException} carrying
     * the {@link TypeMirror}; we stringify that instead.
     */
    private static String mirrorToFqn(Supplier<Class<?>> supplier) {
        try {
            return supplier.get().getName();
        } catch (MirroredTypeException e) {
            return e.getTypeMirror().toString();
        }
    }

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
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
