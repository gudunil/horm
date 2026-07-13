package com.holo.framework.horm.examples.repository;

import com.holo.framework.horm.examples.entity.JpaUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA 用户仓库。
 */
@Repository
public interface JpaUserRepository extends JpaRepository<JpaUser, Long> {

    List<JpaUser> findByEmailContainingOrderByIdAsc(String email);
}
