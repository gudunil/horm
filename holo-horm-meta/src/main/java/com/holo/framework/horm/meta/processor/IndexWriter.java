package com.holo.framework.horm.meta.processor;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p>When the resource already exists (e.g. a hand-maintained index shipped
 * in {@code src/main/resources} or {@code src/test/resources}), the existing
 * entries are merged with the APT-generated ones and de-duplicated, so the
 * APT never silently clobbers manually registered entries.
 */
public final class IndexWriter {

    static final String INDEX_PATH = "META-INF/horm/entities.idx";

    private IndexWriter() {
    }

    public static void write(Filer filer, List<EntityDescriptor> descriptors) throws IOException {
        Set<String> entries = new LinkedHashSet<>(readExistingEntries(filer));
        for (EntityDescriptor d : descriptors) {
            entries.add(d.generatedPackage() + '.' + d.simpleName() + "Meta");
        }
        FileObject res = filer.createResource(StandardLocation.CLASS_OUTPUT, "", INDEX_PATH);
        try (Writer w = res.openWriter()) {
            for (String entry : entries) {
                w.write(entry);
                w.write('\n');
            }
        }
    }

    /**
     * Reads any pre-existing {@code entities.idx} content from the
     * {@link StandardLocation#CLASS_OUTPUT} location so it can be merged with
     * APT-generated entries. Missing files and I/O errors are treated as
     * "no prior content" — the common case when the APT runs first.
     */
    private static Set<String> readExistingEntries(Filer filer) {
        Set<String> existing = new LinkedHashSet<>();
        try {
            FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT, "", INDEX_PATH);
            try (InputStream in = resource.openInputStream();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    existing.add(trimmed);
                }
            }
        } catch (IOException ignored) {
            // No prior index file — normal case when APT runs first.
        }
        return existing;
    }
}
