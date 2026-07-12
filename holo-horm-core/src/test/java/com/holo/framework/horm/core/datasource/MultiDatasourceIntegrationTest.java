package com.holo.framework.horm.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Horm;
import com.holo.framework.horm.core.HormContext;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.core.SimpleDataSourceProvider;
import com.holo.framework.horm.core.TransactionManager;
import com.holo.framework.horm.core.datasource.generated.PrimaryUserQueryMeta;
import com.holo.framework.horm.core.datasource.generated.SecondaryProductQueryMeta;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GeneratedValue;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * Integration test for M5 multi-datasource routing.
 * Verifies that entities with different dataSource configurations
 * are routed to the correct database.
 */
public class MultiDatasourceIntegrationTest {

    private static final String PRIMARY_DB_URL = "jdbc:h2:mem:primary_db;DB_CLOSE_DELAY=-1";
    private static final String SECONDARY_DB_URL = "jdbc:h2:mem:secondary_db;DB_CLOSE_DELAY=-1";

    private Connection primaryConnection;
    private Connection secondaryConnection;

    @BeforeAll
    static void loadRegistry() {
        EntityMetaRegistry.reload();
    }

    @BeforeEach
    void setUp() throws SQLException {
        // Create two separate H2 in-memory databases
        primaryConnection = DriverManager.getConnection(PRIMARY_DB_URL);
        secondaryConnection = DriverManager.getConnection(SECONDARY_DB_URL);

        // Initialize tables in both databases
        initPrimaryDatabase();
        initSecondaryDatabase();

        // Register datasources
        DataSourceRegistry registry = new DataSourceRegistry();
        registry.registerDefault(new SimpleDataSourceProvider(primaryConnection));
        registry.register("secondary", new SimpleDataSourceProvider(secondaryConnection));

        // Install context
        HormContext.install(new HormContext(registry));
    }

    @AfterEach
    void tearDown() throws SQLException {
        TransactionManager.clear();
        HormContext.install(null);

        if (primaryConnection != null && !primaryConnection.isClosed()) {
            primaryConnection.close();
        }
        if (secondaryConnection != null && !secondaryConnection.isClosed()) {
            secondaryConnection.close();
        }

        // EntityMetaRegistry.clear() 是 package-private，测试中不需要调用
        // 因为 @AfterEach 已经清理了连接和上下文
    }

    private void initPrimaryDatabase() throws SQLException {
        try (Statement stmt = primaryConnection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS primary_user (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(255) NOT NULL, " +
                "email VARCHAR(255))");
            stmt.execute("DELETE FROM primary_user");
        }
    }

    private void initSecondaryDatabase() throws SQLException {
        try (Statement stmt = secondaryConnection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS secondary_product (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(255) NOT NULL, " +
                "price DECIMAL(10,2))");
            stmt.execute("DELETE FROM secondary_product");
        }
    }

    @Test
    void testEntityRoutesToCorrectDatasource() {
        // Create and save a PrimaryUser (should go to primary database)
        PrimaryUser user = new PrimaryUser();
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.save();

        // Create and save a SecondaryProduct (should go to secondary database)
        SecondaryProduct product = new SecondaryProduct();
        product.setName("Widget");
        product.setPrice(new BigDecimal("29.99"));
        product.save();

        // Verify user exists in primary database
        assertThat(primaryConnection).isNotNull();
        PrimaryUser foundUser = Model.find(PrimaryUser.class, user.getId());
        assertThat(foundUser).isNotNull();
        assertThat(foundUser.getName()).isEqualTo("Alice");

        // Verify product exists in secondary database
        assertThat(secondaryConnection).isNotNull();
        SecondaryProduct foundProduct = Model.find(SecondaryProduct.class, product.getId());
        assertThat(foundProduct).isNotNull();
        assertThat(foundProduct.getName()).isEqualTo("Widget");
    }

    @Test
    void testCrossDatasourceOperations() {
        // Create entities in both databases
        PrimaryUser user = new PrimaryUser();
        user.setName("Bob");
        user.setEmail("bob@example.com");
        user.save();

        SecondaryProduct product = new SecondaryProduct();
        product.setName("Gadget");
        product.setPrice(new BigDecimal("49.99"));
        product.save();

        // Query both entities
        long userCount = Model.count(PrimaryUser.class);
        long productCount = Model.count(SecondaryProduct.class);

        assertThat(userCount).isEqualTo(1);
        assertThat(productCount).isEqualTo(1);

        // Update user
        user.setName("Bob Updated");
        user.save();

        PrimaryUser updatedUser = Model.find(PrimaryUser.class, user.getId());
        assertThat(updatedUser.getName()).isEqualTo("Bob Updated");

        // Delete product
        product.delete();
        long newProductCount = Model.count(SecondaryProduct.class);
        assertThat(newProductCount).isEqualTo(0);
    }

    @Test
    void testTransactionOnSpecificDatasource() {
        // Transaction on primary datasource
        Horm.tx(() -> {
            PrimaryUser user1 = new PrimaryUser();
            user1.setName("Charlie");
            user1.setEmail("charlie@example.com");
            user1.save();

            PrimaryUser user2 = new PrimaryUser();
            user2.setName("David");
            user2.setEmail("david@example.com");
            user2.save();
        });

        long userCount = Model.count(PrimaryUser.class);
        assertThat(userCount).isEqualTo(2);

        // Transaction on secondary datasource
        Horm.tx(() -> {
            SecondaryProduct product1 = new SecondaryProduct();
            product1.setName("Tool");
            product1.setPrice(new BigDecimal("19.99"));
            product1.save();
        });

        long productCount = Model.count(SecondaryProduct.class);
        assertThat(productCount).isEqualTo(1);
    }

    @Test
    void testQueryOnCorrectDatasource() {
        // Insert data into primary
        PrimaryUser user1 = new PrimaryUser();
        user1.setName("Eve");
        user1.setEmail("eve@example.com");
        user1.save();

        PrimaryUser user2 = new PrimaryUser();
        user2.setName("Frank");
        user2.setEmail("frank@example.com");
        user2.save();

        // Insert data into secondary
        SecondaryProduct product1 = new SecondaryProduct();
        product1.setName("Device");
        product1.setPrice(new BigDecimal("99.99"));
        product1.save();

        // Query primary users - "Eve" contains 'e', "Frank" does not
        var users = Model.query(PrimaryUser.class)
            .where(PrimaryUserQueryMeta.NAME.like("%e%"))
            .list();
        assertThat(users).hasSize(1);
        assertThat(users.get(0).getName()).isEqualTo("Eve");

        // Query secondary products
        var products = Model.query(SecondaryProduct.class)
            .where(SecondaryProductQueryMeta.PRICE.gt(new BigDecimal("50.0")))
            .list();
        assertThat(products).hasSize(1);
        assertThat(products.get(0).getName()).isEqualTo("Device");
    }

    // Test entities

    @Entity(table = "primary_user", dataSource = "")  // Empty means default
    public static class PrimaryUser extends Model<PrimaryUser> {
        @Id
        @GeneratedValue
        private Long id;

        @Column
        private String name;

        @Column
        private String email;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    @Entity(table = "secondary_product", dataSource = "secondary")
    public static class SecondaryProduct extends Model<SecondaryProduct> {
        @Id
        @GeneratedValue
        private Long id;

        @Column
        private String name;

        @Column
        private BigDecimal price;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }
}
