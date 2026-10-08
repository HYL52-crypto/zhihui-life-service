package com.hmdp.annotation;

import java.lang.annotation.*;

/**
 * 滑动窗口限流注解。
 *
 * <p>加在 Controller 方法上即可生效，由 {@code RateLimitAspect} 解析。
 * 计数用 Redis ZSet 实现真正的滑动窗口（不是固定窗口），窗口边界不会出现双倍突发。
 *
 * <pre>
 * 用法示例：
 *   // 同一个用户 10 秒内最多秒杀 5 次
 *   \@RateLimit(type = LimitType.USER, window = 10, limit = 5)
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 时间窗口大小，单位秒
     */
    int window() default 60;

    /**
     * 窗口内允许通过的请求数
     */
    int limit() default 10;

    /**
     * 限流维度：全局 / IP / 用户
     */
    LimitType type() default LimitType.GLOBAL;

    /**
     * 被限流时返回给前端的提示
     */
    String message() default "请求过于频繁，请稍后再试";
}
