package com.holo.framework.horm.examples.controller;

import com.holo.framework.horm.examples.entity.Order;
import com.holo.framework.horm.examples.entity.User;
import com.holo.framework.horm.examples.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 用户 REST 接口，演示 HORM Active Record 与 Spring Boot 集成。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<User> listUsers(@RequestParam(required = false) String email) {
        return userService.listUsers(email);
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable Long id) {
        return userService.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody Map<String, String> body) {
        User user = userService.createUser(body.get("email"), body.get("nickname"));
        return ResponseEntity.created(URI.create("/api/users/" + user.getId())).body(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(userService.updateNickname(id, body.get("nickname")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/orders")
    public ResponseEntity<Order> placeOrder(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        Long productId = Long.valueOf(body.get("productId").toString());
        Integer amount = Integer.valueOf(body.get("amount").toString());
        Order order = userService.placeOrder(id, productId, amount);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(order);
    }
}
