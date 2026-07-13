package com.holo.framework.horm.examples;

import com.holo.framework.horm.starter.EnableHorm;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HORM 示例应用入口。
 *
 * <p>启用 HORM 自动装配与声明式事务代理。
 */
@SpringBootApplication
@EnableHorm
public class HormExamplesApplication {

    public static void main(String[] args) {
        SpringApplication.run(HormExamplesApplication.class, args);
    }
}
