package com.holo.framework.horm.examples.cache;

import com.holo.framework.horm.cache.CachePolicy;
import com.holo.framework.horm.cache.CaffeineCache;
import com.holo.framework.horm.cache.DefaultCacheChain;
import com.holo.framework.horm.cache.WriteStrategy;
import com.holo.framework.horm.core.DataSourceProvider;
import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Product;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 缓存链独立示例。
 *
 * <p>演示如何手动装配 L1 Caffeine 缓存链，以及 {@link Model#findMany(Class, java.util.Collection)}
 * 在缓存命中与未命中时的批量加载行为。
 */
public final class CacheExample {

    private static final String H2_URL = "jdbc:h2:mem:cache_example;MODE=MySQL;DB_CLOSE_DELAY=-1";

    public static void main(String[] args) throws Exception {
        Result result = runExample();
        System.out.println("Saved products: " + result.phoneId() + ", " + result.padId());
        System.out.println("Cache size after invalidation: " + result.sizeAfterInvalidation());
        System.out.println("First findMany loaded: " + result.firstLoadSize() + ", cache size: " + result.sizeAfterFirstLoad());
        System.out.println("Second findMany loaded: " + result.secondLoadSize() + ", cache size: " + result.sizeAfterSecondLoad());
        System.out.println("Same instance for phone? " + result.sameInstance());
    }

    /**
     * 执行缓存示例并返回可断言的结果。
     */
    public static Result runExample() throws Exception {
        EntityMetaRegistry.reload();

        try (Connection conn = DriverManager.getConnection(H2_URL);
             Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS products (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "  sku VARCHAR(64) NOT NULL, " +
                "  name VARCHAR(128) NOT NULL, " +
                "  price DECIMAL(19, 4) NOT NULL, " +
                "  UNIQUE KEY uk_products_sku (sku)" +
                ")");
        }

        CachePolicy policy = CachePolicy.builder()
            .ttl(Duration.ofMinutes(30))
            .writeStrategy(WriteStrategy.THROUGH)
            .maxEntries(1000)
            .build();
        CaffeineCache l1 = new CaffeineCache("products-l1", policy);
        DefaultCacheChain chain = new DefaultCacheChain(l1);

        DataSourceProvider provider = () -> {
            Connection c = DriverManager.getConnection(H2_URL);
            c.setAutoCommit(true);
            return c;
        };

        Horm.install(new HormContext(provider, chain));

        try {
            Product phone = new Product();
            phone.setSku("PHONE-001");
            phone.setName("Holo Phone");
            phone.setPrice(new BigDecimal("4999.00"));
            phone.save();

            Product pad = new Product();
            pad.setSku("PAD-001");
            pad.setName("Holo Pad");
            pad.setPrice(new BigDecimal("6999.00"));
            pad.save();

            chain.invalidateAll();
            long sizeAfterInvalidation = chain.stats().estimatedSize();

            Map<Object, Product> first = Model.findMany(Product.class, List.of(phone.getId(), pad.getId()));
            long sizeAfterFirstLoad = chain.stats().estimatedSize();

            Map<Object, Product> second = Model.findMany(Product.class, List.of(phone.getId(), pad.getId()));
            long sizeAfterSecondLoad = chain.stats().estimatedSize();

            return new Result(
                phone.getId(), pad.getId(),
                sizeAfterInvalidation, first.size(), sizeAfterFirstLoad,
                second.size(), sizeAfterSecondLoad,
                first.get(phone.getId()) == second.get(phone.getId()));
        } finally {
            HormContext.install(null);
            chain.close();
        }
    }

    public record Result(
        Long phoneId,
        Long padId,
        long sizeAfterInvalidation,
        int firstLoadSize,
        long sizeAfterFirstLoad,
        int secondLoadSize,
        long sizeAfterSecondLoad,
        boolean sameInstance
    ) {
    }
}
