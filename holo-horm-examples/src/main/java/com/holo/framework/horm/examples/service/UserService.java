package com.holo.framework.horm.examples.service;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Order;
import com.holo.framework.horm.examples.entity.Product;
import com.holo.framework.horm.examples.entity.User;
import com.holo.framework.horm.examples.entity.generated.UserQueryMeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 用户业务服务，展示声明式事务、Active Record 与查询 DSL。
 */
@Service
public class UserService {

    @Transactional
    public User createUser(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.save();
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(Model.find(User.class, id));
    }

    @Transactional(readOnly = true)
    public List<User> listUsers(String emailPattern) {
        if (emailPattern == null || emailPattern.isBlank()) {
            return Model.all(User.class);
        }
        return Model.query(User.class)
            .where(UserQueryMeta.EMAIL.like("%" + emailPattern + "%"))
            .orderBy(UserQueryMeta.CREATED_AT, com.holo.framework.horm.core.query.Order.DESC)
            .list();
    }

    @Transactional
    public User updateNickname(Long id, String nickname) {
        User user = Model.find(User.class, id);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + id);
        }
        user.setNickname(nickname);
        user.save();
        return user;
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = Model.find(User.class, id);
        if (user != null) {
            user.delete();
        }
    }

    @Transactional
    public Order placeOrder(Long userId, Long productId, int amount) {
        User user = Model.find(User.class, userId);
        Product product = Model.find(Product.class, productId);
        if (user == null || product == null) {
            throw new IllegalArgumentException("User or product not found");
        }

        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setAmount(amount);
        order.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(amount)));
        order.setStatus("PAID");
        order.save();
        return order;
    }
}
