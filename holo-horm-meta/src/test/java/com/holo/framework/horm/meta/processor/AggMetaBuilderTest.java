package com.holo.framework.horm.meta.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

class AggMetaBuilderTest {

    private static final String MODEL_SOURCE = """
        package com.holo.framework.horm.core;
        public abstract class Model<T> {
        }
        """;

    private static final String PRODUCT_SOURCE = """
        package test;
        import com.holo.framework.horm.meta.annotation.Entity;
        import com.holo.framework.horm.meta.annotation.Id;
        import com.holo.framework.horm.meta.annotation.Column;
        import com.holo.framework.horm.meta.annotation.GenerationType;
        import com.holo.framework.horm.core.Model;
        import java.math.BigDecimal;

        @Entity(table = "products")
        public class Product extends Model<Product> {
            @Id(strategy = GenerationType.IDENTITY)
            private Long id;

            @Column
            private String name;

            @Column
            private BigDecimal price;

            @Column
            private Integer stock;

            public Long getId() { return id; }
            public void setId(Long id) { this.id = id; }
            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public BigDecimal getPrice() { return price; }
            public void setPrice(BigDecimal price) { this.price = price; }
            public Integer getStock() { return stock; }
            public void setStock(Integer stock) { this.stock = stock; }
        }
        """;

    @Test
    void generatesAggMetaClass() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var aggFile = comp.generatedSourceFile("test.generated.ProductAggMeta");
        assertThat(aggFile).isPresent();
    }

    @Test
    void generatesCountConstants() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        assertThat(source).contains("public static final AggExpr<Long> COUNT");
        assertThat(source).contains("Aggregates.count()");
        assertThat(source).contains("public static final AggExpr<Long> COUNT_ID");
        assertThat(source).contains("Aggregates.count(ProductQueryMeta.ID)");
    }

    @Test
    void generatesNumericAggregates() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        assertThat(source).contains("SUM_PRICE");
        assertThat(source).contains("AVG_PRICE");
        assertThat(source).contains("MAX_PRICE");
        assertThat(source).contains("MIN_PRICE");
        assertThat(source).contains("SUM_STOCK");
        assertThat(source).contains("AVG_STOCK");
        assertThat(source).contains("MAX_STOCK");
        assertThat(source).contains("MIN_STOCK");
    }

    @Test
    void generatesComparableAggregates() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        assertThat(source).contains("MAX_NAME");
        assertThat(source).contains("MIN_NAME");
    }

    @Test
    void doesNotGenerateSumAvgForString() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        assertThat(source).doesNotContain("SUM_NAME");
        assertThat(source).doesNotContain("AVG_NAME");
    }

    @Test
    void constantNamesMatchQueryMetaConvention() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        // snake_case field name "createdAt" → const "CREATED_AT"
        // Product has no createdAt, but we can verify the naming convention
        // by checking that field names use uppercase snake_case
        assertThat(source).contains("SUM_PRICE");  // price → PRICE
        assertThat(source).contains("SUM_STOCK");  // stock → STOCK
    }

    @Test
    void aggMetaReferencesQueryMetaConstants() {
        Compilation comp = compile(PRODUCT_SOURCE);
        assertThat(comp.status()).isEqualTo(Compilation.Status.SUCCESS);

        var source = sourceContent(comp, "test.generated.ProductAggMeta");
        assertThat(source).contains("ProductQueryMeta.PRICE");
        assertThat(source).contains("ProductQueryMeta.STOCK");
        assertThat(source).contains("ProductQueryMeta.NAME");
    }

    private Compilation compile(String... sources) {
        var builder = javac()
            .withProcessors(new HormEntityProcessor())
            .withClasspathFrom(getClass().getClassLoader());

        JavaFileObject[] allSources = new JavaFileObject[sources.length + 1];
        allSources[0] = JavaFileObjects.forSourceString("Model", MODEL_SOURCE);
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = src.substring(src.indexOf("class ") + 6, src.indexOf(" extends"));
            allSources[i + 1] = JavaFileObjects.forSourceString("test." + className.trim(), src);
        }
        return builder.compile(allSources);
    }

    private String sourceContent(Compilation comp, String className) {
        return comp.generatedSourceFile(className)
            .map(jfo -> {
                try {
                    return jfo.getCharContent(false).toString();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .orElse("");
    }
}
