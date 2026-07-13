package com.holo.framework.horm.examples;

import com.holo.framework.horm.core.EntityMetaRegistry;
import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Order;
import com.holo.framework.horm.examples.entity.Product;
import com.holo.framework.horm.examples.entity.User;
import com.holo.framework.horm.examples.entity.generated.UserQueryMeta;
import com.holo.framework.horm.examples.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HORM 示例应用集成测试。
 *
 * <p>验证 Spring Boot 自动装配、自动迁移、Active Record CRUD、查询 DSL 与 REST API。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class UserCrudIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void setUp() {
        EntityMetaRegistry.reload();
    }

    @Test
    void contextLoads() {
        assertThat(userService).isNotNull();
    }

    @Test
    void userCrudViaService() {
        User created = userService.createUser("alice@holo.dev", "Alice");
        assertThat(created.getId()).isNotNull();
        assertThat(created.getEmail()).isEqualTo("alice@holo.dev");

        User found = Model.find(User.class, created.getId());
        assertThat(found.getNickname()).isEqualTo("Alice");

        User updated = userService.updateNickname(created.getId(), "Alice Li");
        assertThat(updated.getNickname()).isEqualTo("Alice Li");

        List<User> users = Model.query(User.class)
            .where(UserQueryMeta.EMAIL.eq("alice@holo.dev"))
            .list();
        assertThat(users).hasSize(1);

        userService.deleteUser(created.getId());
        assertThat(Model.find(User.class, created.getId())).isNull();
    }

    @Test
    void placeOrder() {
        Product product = new Product();
        product.setSku("TEST-001");
        product.setName("Test Product");
        product.setPrice(new BigDecimal("99.99"));
        product.save();

        User user = userService.createUser("bob@holo.dev", "Bob");
        Order order = userService.placeOrder(user.getId(), product.getId(), 3);

        assertThat(order.getId()).isNotNull();
        assertThat(order.getTotalPrice()).isEqualByComparingTo(new BigDecimal("299.97"));
        assertThat(order.getStatus()).isEqualTo("PAID");
    }

    @Test
    void restApiSmokeTest() {
        String baseUrl = "http://localhost:" + port + "/api/users";

        var createResponse = restTemplate.postForEntity(
            baseUrl,
            Map.of("email", "carol@holo.dev", "nickname", "Carol"),
            User.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        User created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();

        var listResponse = restTemplate.getForEntity(baseUrl, User[].class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotEmpty();
    }
}
