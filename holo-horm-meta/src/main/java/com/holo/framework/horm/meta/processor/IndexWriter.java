package com.holo.framework.horm.meta.processor;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes the {@code META-INF/horm/entities.idx} resource listing every
 * generated {@code XxxMeta} class by fully-qualified name.
 *
 * <p>At startup (M1-6), {@code EntityMetaRegistry} locates this resource via
 * {@code ClassLoader.getResources("META-INF/horm/entities.idx")}, reads each
 * line, invokes {@code Class.forName(metaClassName)}, and calls the static
 * {@code entityMeta()} factory to register the entity.
 *
 * <p>The index is written once, during the
 * {@link javax.annotation.processing.RoundEnvironment#processingOver()} round,
 * so that all descriptors collected across rounds are included.
 */
public final class IndexWriter {

    static final String INDEX_PATH = "META-INF/horm/entities.idx";

    private IndexWriter() {
    }

    public static void write(Filer filer, List<EntityDescriptor> descriptors) throws IOException {
        FileObject res = filer.createResource(StandardLocation.CLASS_OUTPUT, "", INDEX_PATH);
        try (Writer w = res.openWriter()) {
            for (EntityDescriptor d : descriptors) {
                w.write(d.generatedPackage());
                w.write('.');
                w.write(d.simpleName());
                w.write("Meta\n");
            }
        }
    }
}
