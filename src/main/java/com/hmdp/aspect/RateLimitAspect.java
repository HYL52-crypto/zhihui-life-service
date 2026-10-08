package com.hmdp.aspect;

import com.hmdp.annotation.LimitType;
import com.hmdp.annotation.RateLimit;
import com.hmdp.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 滑动窗口限流切面。
 *
 * <p>计数逻辑放在 Lua 脚本里，保证「清理过期记录 + 统计 + 写入」三步的原子性。
 * 如果拆成三条 Redis 命令，高并发下会出现窗口边界竞态。
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);

        Long result = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                java.util.Collections.singletonList(key),
                String.valueOf(rateLimit.window()),
                String.valueOf(rateLimit.limit()),
                String.valueOf(System.currentTimeMillis()),
                UUID.randomUUID().toString()
        );

        // Lua 返回 1 放行，0 拒绝
        if (result == null || result == 0) {
            log.warn("触发限流 key={} window={}s limit={}", key, rateLimit.window(), rateLimit.limit());
            throw new RateLimitException(rateLimit.message());
        }

        return joinPoint.proceed();
    }

    /**
     * 按限流维度拼 key：全局共享一个桶，IP 和用户各自一个桶。
     */
    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodKey = method.getDeclaringClass().getSimpleName() + ":" + method.getName();

        LimitType type = rateLimit.type();
        switch (type) {
            case IP:
                return "limit:ip:" + methodKey + ":" + getIp();
            case USER:
                // 用户维度：未登录时降级为 IP 维度，否则匿名请求会共用同一个桶
                Long userId = currentUserId();
                if (userId != null) {
                    return "limit:user:" + methodKey + ":" + userId;
                }
                return "limit:ip:" + methodKey + ":" + getIp();
            case GLOBAL:
            default:
                return "limit:global:" + methodKey;
        }
    }

    private String getIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private Long currentUserId() {
        try {
            if (UserHolder.getUser() == null) {
                return null;
            }
            return UserHolder.getUser().getId();
        } catch (Exception e) {
            return null;
        }
    }
}
