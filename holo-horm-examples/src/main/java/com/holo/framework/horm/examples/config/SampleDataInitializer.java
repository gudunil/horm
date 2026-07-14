package com.holo.framework.horm.examples.config;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Order;
import com.holo.framework.horm.examples.entity.Product;
import com.holo.framework.horm.examples.entity.User;
import com.holo.framework.horm.examples.entity.generated.ProductQueryMeta;
import com.holo.framework.horm.examples.entity.generated.UserQueryMeta;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 启动时预置示例数据，便于前端页面和 DSL 查询示例直接体验。
 */
@Component
public class SampleDataInitializer implements CommandLineRunner {

    @Override
    @Transactional
    public void run(String... args) {
        // 初始化商品
        Product phone = ensureProduct("PHONE-001", "Holo Phone", new BigDecimal("4999.00"));
        Product pad = ensureProduct("PAD-001", "Holo Pad", new BigDecimal("6999.00"));
        Product laptop = ensureProduct("LAPTOP-001", "Holo Laptop", new BigDecimal("9999.00"));

        // 初始化用户
        User alice = ensureUser("alice@holo.dev", "Alice");
        User bob = ensureUser("bob@holo.dev", "Bob");
        User charlie = ensureUser("charlie@holo.dev", "Charlie");

        // 初始化订单
        ensureOrder(alice.getId(), phone.getId(), 2, new BigDecimal("9998.00"), "COMPLETED", 30);
        ensureOrder(alice.getId(), pad.getId(), 1, new BigDecimal("6999.00"), "PENDING", 15);
        ensureOrder(bob.getId(), laptop.getId(), 1, new BigDecimal("9999.00"), "COMPLETED", 45);
        ensureOrder(bob.getId(), phone.getId(), 3, new BigDecimal("14997.00"), "SHIPPED", 20);
        ensureOrder(charlie.getId(), pad.getId(), 2, new BigDecimal("13998.00"), "PENDING", 10);
        ensureOrder(charlie.getId(), laptop.getId(), 1, new BigDecimal("9999.00"), "COMPLETED", 60);
    }

    private Product ensureProduct(String sku, String name, BigDecimal price) {
        return Model.query(Product.class)
            .where(ProductQueryMeta.SKU.eq(sku))
            .findFirst()
            .orElseGet(() -> {
                Product product = new Product();
                product.setSku(sku);
                product.setName(name);
                product.setPrice(price);
                product.save();
                return product;
            });
    }

    private User ensureUser(String email, String nickname) {
        return Model.query(User.class)
            .where(UserQueryMeta.EMAIL.eq(email))
            .findFirst()
            .orElseGet(() -> {
                User user = new User();
                user.setEmail(email);
                user.setNickname(nickname);
                user.save();
                return user;
            });
    }

    private void ensureOrder(Long userId, Long productId, int amount, BigDecimal totalPrice, String status, int daysAgo) {
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setAmount(amount);
        order.setTotalPrice(totalPrice);
        order.setStatus(status);
        order.save();
    }
}
