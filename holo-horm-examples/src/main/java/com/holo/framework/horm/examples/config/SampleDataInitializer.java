package com.holo.framework.horm.examples.config;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Product;
import com.holo.framework.horm.examples.entity.generated.ProductQueryMeta;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 启动时预置示例商品数据，便于前端页面直接体验下单流程。
 */
@Component
public class SampleDataInitializer implements CommandLineRunner {

    @Override
    @Transactional
    public void run(String... args) {
        ensureProduct("PHONE-001", "Holo Phone", new BigDecimal("4999.00"));
        ensureProduct("PAD-001", "Holo Pad", new BigDecimal("6999.00"));
    }

    private void ensureProduct(String sku, String name, BigDecimal price) {
        boolean exists = !Model.query(Product.class)
            .where(ProductQueryMeta.SKU.eq(sku))
            .list()
            .isEmpty();
        if (exists) {
            return;
        }
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setPrice(price);
        product.save();
    }
}
