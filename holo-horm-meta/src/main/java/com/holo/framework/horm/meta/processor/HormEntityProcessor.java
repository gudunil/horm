package com.holo.framework.horm.meta.processor;

import com.holo.framework.horm.meta.annotation.Entity;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * APT entry point for HORM entity processing. Scans {@code @Entity}-annotated
 * types, produces an {@link EntityDescriptor} for each, and drives the JavaPoet
 * builders that emit the zero-reflection companion classes.
 *
 * <p>Per entity, the processor invokes:
 * <ul>
 *   <li>{@link EntityDescriptorParser} — parses the {@code @Entity} type into an
 *       {@link EntityDescriptor} IR (permissive; does not report contract violations)</li>
 *   <li>{@link EntityValidator} — compile-time validation of the descriptor
 *       (reports {@code @Id} presence, {@code Model<T>} inheritance, non-final
 *       fields, type-mapping support as {@code ERROR} diagnostics)</li>
 *   <li>{@link MetaClassBuilder} — generates {@code XxxMeta} (field metadata + {@code entityMeta()} factory)</li>
 *   <li>{@link MapperBuilder} — generates {@code XxxMapper} (zero-reflection {@code Row} bidirectional mapper)</li>
 *   <li>{@link QueryMetaBuilder} — generates {@code XxxQueryMeta} (type-safe {@code TypedField} constants)</li>
 * </ul>
 *
 * <p>On the final round ({@link RoundEnvironment#processingOver()}), it writes
 * {@code META-INF/horm/entities.idx} via {@link IndexWriter}, listing every
 * generated {@code XxxMeta} class for runtime discovery by
 * {@code EntityMetaRegistry} (M1-6).
 *
 * <p>The {@code process()} method returns {@code true} to claim ownership of
 * the {@code @Entity} annotation type, preventing other processors from
 * re-processing the same elements.
 */
@SupportedAnnotationTypes("com.holo.framework.horm.meta.annotation.Entity")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class HormEntityProcessor extends AbstractProcessor {

    private final List<EntityDescriptor> descriptors = new ArrayList<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!descriptors.isEmpty()) {
                messager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "[HORM] Parsed " + descriptors.size() + " entity(es); companion classes generated"
                );
                try {
                    IndexWriter.write(processingEnv.getFiler(), descriptors);
                } catch (IOException ex) {
                    messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Failed to write entities.idx: " + ex.getMessage()
                    );
                }
            }
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Entity.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                messager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Entity can only be applied to classes",
                    element
                );
                continue;
            }
            TypeElement type = (TypeElement) element;
            try {
                EntityDescriptor descriptor = EntityDescriptorParser.parse(type, processingEnv);
                EntityValidator.validate(descriptor, type, processingEnv);
                descriptors.add(descriptor);
                MetaClassBuilder.build(descriptor, processingEnv.getFiler());
                MapperBuilder.build(descriptor, processingEnv.getFiler());
                QueryMetaBuilder.build(descriptor, processingEnv.getFiler());
            } catch (Exception ex) {
                messager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to process @Entity " + type.getQualifiedName() + ": " + ex.getMessage(),
                    element
                );
            }
        }
        return true;
    }

    /**
     * Exposed for testing — returns a snapshot of descriptors parsed so far.
     * Production code (M1-4 builders) should consume descriptors via the same
     * instance during {@link #process(Set, RoundEnvironment)}.
     */
    List<EntityDescriptor> descriptors() {
        return List.copyOf(descriptors);
    }

    private Messager messager() {
        return processingEnv.getMessager();
    }
}
