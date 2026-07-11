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
    static final String TRANSACTION_INDEX_PATH = "META-INF/horm/transactions.idx";

    private IndexWriter() {
    }

    public static void write(Filer filer, List<EntityDescriptor> descriptors) throws IOException {
        Set<String> entries = new LinkedHashSet<>(readExistingEntries(filer, INDEX_PATH));
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
     * Writes the {@code META-INF/horm/transactions.idx} resource listing every
     * generated transaction advisor class by fully-qualified name.
     */
    public static void writeTransactionIndex(Filer filer, List<String> advisorNames) throws IOException {
        Set<String> entries = new LinkedHashSet<>(readExistingEntries(filer, TRANSACTION_INDEX_PATH));
        entries.addAll(advisorNames);
        FileObject res = filer.createResource(StandardLocation.CLASS_OUTPUT, "", TRANSACTION_INDEX_PATH);
        try (Writer w = res.openWriter()) {
            for (String entry : entries) {
                w.write(entry);
                w.write('\n');
            }
        }
    }

    private static Set<String> readExistingEntries(Filer filer, String path) {
        Set<String> existing = new LinkedHashSet<>();
        try {
            FileObject resource = filer.getResource(StandardLocation.CLASS_OUTPUT, "", path);
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
