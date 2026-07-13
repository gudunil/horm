package com.holo.framework.horm.examples.controller;

import com.holo.framework.horm.examples.dto.UserDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用 Spring JdbcTemplate 实现用户 CRUD，用于与 HORM 对比。
 */
@RestController
@RequestMapping("/api/jdbc/users")
public class JdbcTemplateController {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<UserDto> mapper = (rs, rowNum) -> new UserDto(
        rs.getLong("id"),
        rs.getString("email"),
        rs.getString("nickname"),
        rs.getLong("version"),
        Optional.ofNullable(rs.getTimestamp("created_at")).map(Timestamp::toInstant).orElse(null),
        Optional.ofNullable(rs.getTimestamp("updated_at")).map(Timestamp::toInstant).orElse(null)
    );

    public JdbcTemplateController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<UserDto> listUsers(@RequestParam(required = false) String email) {
        if (email == null || email.isBlank()) {
            return jdbcTemplate.query("SELECT * FROM users ORDER BY id", mapper);
        }
        return jdbcTemplate.query("SELECT * FROM users WHERE email LIKE ? ORDER BY id", mapper, "%" + email + "%");
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        try {
            UserDto user = jdbcTemplate.queryForObject("SELECT * FROM users WHERE id = ?", mapper, id);
            return ResponseEntity.ok(user);
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<UserDto> createUser(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String nickname = body.get("nickname");
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                new String[]{"id"});
            ps.setString(1, email);
            ps.setString(2, nickname);
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();
        return ResponseEntity.created(URI.create("/api/jdbc/users/" + id)).body(getUser(id).getBody());
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String nickname = body.get("nickname");
        int rows = jdbcTemplate.update(
            "UPDATE users SET nickname = ?, version = version + 1 WHERE id = ?",
            nickname, id);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(getUser(id).getBody());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        return ResponseEntity.noContent().build();
    }
}
