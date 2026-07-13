package com.holo.framework.horm.examples.controller;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.examples.entity.Product;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品 REST 接口，供前端页面下拉选择使用。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @GetMapping
    public ResponseEntity<List<Product>> listProducts() {
        return ResponseEntity.ok(Model.all(Product.class));
    }
}
