package com.holo.framework.horm.examples.dto;

import java.time.Instant;

/**
 * 统一用户响应 DTO，用于多框架 REST 接口对比。
 */
public class UserDto {

    private Long id;
    private String email;
    private String nickname;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public UserDto() {
    }

    public UserDto(Long id, String email, String nickname, Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.nickname = nickname;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
