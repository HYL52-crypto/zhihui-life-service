package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置。
 *
 * <p>二级缓存结构：Caffeine（进程内） → Redis（分布式） → MySQL。
 * 本地缓存命中时连 Redis 都不用访问，适合热点店铺这种读多写少的数据。
 *
 * <p>为什么本地缓存 TTL 要短（60 秒）？
 * 本地缓存无法像 Redis 那样被其他实例感知，实例之间的数据不一致只能靠过期时间来收敛。
 * TTL 越短，不一致窗口越小，但要和命中率做权衡。
 */
@Configuration
public class CaffeineConfig {

    @Bean
    public com.github.benmanes.caffeine.cache.Cache<Long, Shop> shopLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)
                .maximumSize(10_000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
