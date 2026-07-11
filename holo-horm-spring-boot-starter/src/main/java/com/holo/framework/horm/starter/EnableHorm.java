package com.holo.framework.horm.starter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 启用 HORM 框架的自动配置。
 *
 * <p>在 Spring Boot 应用的主配置类上添加此注解，将导入 {@link HormAutoConfiguration}，
 * 自动创建 {@link com.holo.framework.horm.core.HormContext} 并安装到运行时。
 *
 * <p>示例：
 * <pre>{@code
 * @SpringBootApplication
 * @EnableHorm
 * public class Application {
 *     public static void main(String[] args) {
 *         SpringApplication.run(Application.class, args);
 *     }
 * }
 * }</pre>
 *
 * @author Holo Framework Team
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(HormAutoConfiguration.class)
public @interface EnableHorm {
}
