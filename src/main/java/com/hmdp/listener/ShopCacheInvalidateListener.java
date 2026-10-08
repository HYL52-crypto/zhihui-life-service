package com.hmdp.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.CacheInvalidateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * 缓存删除补偿消费者。
 * <p>
 * 缓存一致性方案的三层保险：
 * <ol>
 *   <li>第一层：更新数据库后同步删一次缓存（正常路径，99% 的请求走这里就结束了）；</li>
 *   <li>第二层：删失败就发 Kafka，这里异步重试删，最多 3 次 + 退避等待；</li>
 *   <li>第三层：3 次都失败写进 Redis 死信 list，由定时任务周期性兜底重删，
 *              同时缓存本身还有真实 TTL，最坏情况也会自动过期。</li>
 * </ol>
 * 面试追问「为什么不直接删两次」：同步链路上的重试会拖慢接口响应时间，
 * 而且失败往往是 Redis 抖动，立刻重试大概率还是失败；异步 + 退避才有意义。
 */
@Slf4j
@Component
public class ShopCacheInvalidateListener {

    /** 最大补偿次数 */
    private static final int MAX_RETRY = 3;

    /** 死信队列：Redis list，存补偿失败的原始消息 */
    public static final String DEAD_LETTER_KEY = "cache:invalidate:deadletter";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaConfig.TOPIC_SHOP_CACHE_INVALIDATE,
            groupId = KafkaConfig.GROUP_SHOP_CACHE_INVALIDATE)
    public void onInvalidate(String message, Acknowledgment ack) {
        try {
            CacheInvalidateMessage msg = objectMapper.readValue(message, CacheInvalidateMessage.class);
            boolean deleted = deleteWithRetry(msg.getShopId());

            if (deleted) {
                log.info("[缓存补偿] 第 {} 次补偿删除成功, shopId={}", msg.getRetry(), msg.getShopId());
            } else {
                // 重试耗尽：丢进死信，交给定时任务兜底，不能直接把消息吞掉
                stringRedisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, message);
                log.error("[缓存补偿] 重试 {} 次仍失败，已写入死信, shopId={}", MAX_RETRY, msg.getShopId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[缓存补偿] 消费异常，不提交 offset, message={}", message, e);
        }
    }

    /**
     * 带退避的重试删除。
     * 删完再 hasKey 确认一次——只调用 delete 不校验，遇到命令超时其实可能没删掉。
     */
    private boolean deleteWithRetry(Long shopId) {
        String key = CACHE_SHOP_KEY + shopId;
        for (int i = 1; i <= MAX_RETRY; i++) {
            try {
                stringRedisTemplate.delete(key);
                Boolean exists = stringRedisTemplate.hasKey(key);
                if (exists == null || !exists) {
                    return true;
                }
                log.warn("[缓存补偿] 第 {} 次删除后 key 仍存在, shopId={}", i, shopId);
            } catch (Exception e) {
                log.warn("[缓存补偿] 第 {} 次删除抛异常, shopId={}", i, shopId, e);
            }
            // 退避：200ms / 400ms / 600ms，避免抖动期间密集重试
            try {
                Thread.sleep(200L * i);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
