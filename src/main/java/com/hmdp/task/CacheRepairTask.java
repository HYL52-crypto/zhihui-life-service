package com.hmdp.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.CacheInvalidateMessage;
import com.hmdp.listener.ShopCacheInvalidateListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * 死信兜底：每 5 分钟把「补偿 3 次都没删掉」的缓存 key 再捞出来删一遍。
 * <p>
 * 为什么还需要这一层：Kafka 消费者里已经重试了 3 次，如果 3 次都失败，
 * 说明当时 Redis 大概率处于比较严重的问题中。等几分钟再试，恢复概率高很多。
 * 这是「最终一致」里最后一道人工兜底之前的自动兜底。
 */
@Slf4j
@Component
public class CacheRepairTask {

    /** 单次最多处理多少条死信，防止一次拉爆 */
    private static final int BATCH = 100;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void repairDeadLetter() {
        String key = ShopCacheInvalidateListener.DEAD_LETTER_KEY;
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size == null || size <= 0) {
            return;
        }
        int total = size.intValue();
        int success = 0;
        int remain = 0;

        for (int i = 0; i < Math.min(total, BATCH); i++) {
            String raw = stringRedisTemplate.opsForList().rightPop(key);
            if (raw == null) {
                break;
            }
            try {
                CacheInvalidateMessage msg = objectMapper.readValue(raw, CacheInvalidateMessage.class);
                stringRedisTemplate.delete(CACHE_SHOP_KEY + msg.getShopId());
                Boolean exists = stringRedisTemplate.hasKey(CACHE_SHOP_KEY + msg.getShopId());
                if (exists == null || !exists) {
                    success++;
                } else {
                    // 还是删不掉，塞回死信等下一轮
                    stringRedisTemplate.opsForList().leftPush(key, raw);
                    remain++;
                }
            } catch (Exception e) {
                log.error("[缓存兜底] 处理死信失败, raw={}", raw, e);
                stringRedisTemplate.opsForList().leftPush(key, raw);
                remain++;
            }
        }
        log.info("[缓存兜底] 本轮处理死信 {} 条，成功 {} 条，仍失败 {} 条",
                Math.min(total, BATCH), success, remain);
    }
}
