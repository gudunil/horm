package com.holo.framework.horm.examples.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holo.framework.horm.examples.dto.UserDto;
import com.holo.framework.horm.examples.entity.MpUser;
import com.holo.framework.horm.examples.mapper.MpUserMapper;
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
 * 使用 MyBatis-Plus 实现用户 CRUD，用于与 HORM 对比。
 */
@RestController
@RequestMapping("/api/mybatis/users")
public class MyBatisPlusController {

    private final MpUserMapper mpUserMapper;

    public MyBatisPlusController(MpUserMapper mpUserMapper) {
        this.mpUserMapper = mpUserMapper;
    }

    @GetMapping
    public List<MpUser> listUsers(@RequestParam(required = false) String email) {
        LambdaQueryWrapper<MpUser> wrapper = new LambdaQueryWrapper<>();
        if (email != null && !email.isBlank()) {
            wrapper.like(MpUser::getEmail, email);
        }
        wrapper.orderByAsc(MpUser::getId);
        return mpUserMapper.selectList(wrapper);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MpUser> getUser(@PathVariable Long id) {
        MpUser user = mpUserMapper.selectById(id);
        return user != null ? ResponseEntity.ok(user) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<MpUser> createUser(@RequestBody Map<String, String> body) {
        MpUser user = new MpUser();
        user.setEmail(body.get("email"));
        user.setNickname(body.get("nickname"));
        mpUserMapper.insert(user);
        return ResponseEntity.created(URI.create("/api/mybatis/users/" + user.getId())).body(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MpUser> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        MpUser user = new MpUser();
        user.setId(id);
        user.setNickname(body.get("nickname"));
        int rows = mpUserMapper.updateById(user);
        if (rows == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mpUserMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        mpUserMapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
