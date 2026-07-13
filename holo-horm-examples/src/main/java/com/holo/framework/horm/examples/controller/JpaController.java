package com.holo.framework.horm.examples.controller;

import com.holo.framework.horm.examples.entity.JpaUser;
import com.holo.framework.horm.examples.repository.JpaUserRepository;
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
 * 使用 Spring Data JPA 实现用户 CRUD，用于与 HORM 对比。
 */
@RestController
@RequestMapping("/api/jpa/users")
public class JpaController {

    private final JpaUserRepository jpaUserRepository;

    public JpaController(JpaUserRepository jpaUserRepository) {
        this.jpaUserRepository = jpaUserRepository;
    }

    @GetMapping
    public List<JpaUser> listUsers(@RequestParam(required = false) String email) {
        if (email == null || email.isBlank()) {
            return jpaUserRepository.findAll();
        }
        return jpaUserRepository.findByEmailContainingOrderByIdAsc(email);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JpaUser> getUser(@PathVariable Long id) {
        return jpaUserRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<JpaUser> createUser(@RequestBody Map<String, String> body) {
        JpaUser user = new JpaUser();
        user.setEmail(body.get("email"));
        user.setNickname(body.get("nickname"));
        JpaUser saved = jpaUserRepository.save(user);
        return ResponseEntity.created(URI.create("/api/jpa/users/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<JpaUser> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        JpaUser existing = jpaUserRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        existing.setNickname(body.get("nickname"));
        return ResponseEntity.ok(jpaUserRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        jpaUserRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
