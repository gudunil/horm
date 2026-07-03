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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * APT entry point for HORM entity processing. Scans {@code @Entity}-annotated
 * types and produces an {@link EntityDescriptor} for each, deferring code
 * generation to the JavaPoet builders that arrive in M1-4.
 *
 * <p>This M1-3 milestone release:
 * <ul>
 *   <li>Parses every {@code @Entity} class via {@link EntityDescriptorParser}</li>
 *   <li>Collects descriptors across processing rounds</li>
 *   <li>Emits a single {@code NOTE} diagnostic summarising how many entities
 *       were parsed when {@link RoundEnvironment#processingOver()} is reached</li>
 *   <li>Reports a compile-time {@code ERROR} when {@code @Entity} is applied to
 *       a non-class element, or when parsing throws</li>
 * </ul>
 *
 * <p>The {@code process()} method returns {@code true} to claim ownership of
 * the {@code @Entity} annotation type, preventing other processors from
 * re-processing the same elements.
 *
 * <p>M1-4 will extend this class to invoke {@code MetaClassBuilder},
 * {@code MapperBuilder}, {@code QueryMetaBuilder} on each descriptor and write
 * the {@code META-INF/horm/entities.idx} index file via {@code IndexWriter}.
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
                    "[HORM] Parsed " + descriptors.size() + " entity(es); generation deferred to M1-4"
                );
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
                descriptors.add(descriptor);
                // M1-4 extension point: MetaClassBuilder / MapperBuilder / QueryMetaBuilder / IndexWriter
            } catch (Exception ex) {
                messager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to parse @Entity " + type.getQualifiedName() + ": " + ex.getMessage(),
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
