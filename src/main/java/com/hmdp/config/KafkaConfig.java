package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 配置：秒杀下单由「同步落库」改为「Lua 预检 + 消息异步下单」。
 *
 * <p>为什么用消息队列：秒杀峰值下，如果下单请求直接打数据库，数据库连接会被瞬间打满。
 * Lua 脚本在 Redis 里完成库存与一人一单的校验（原子操作）后立即返回订单号，
 * 真正的落库 + 扣减库存交给消费者按数据库能承受的速度消费，实现削峰填谷。
 *
 * <p>为什么选 Kafka（对比 RabbitMQ）：Kafka 的分区顺序写 + 消费者组重平衡，
 * 在秒杀这种「高吞吐、允许短暂延迟」的场景下吞吐更高；
 * 这里以订单号作为消息 key，保证同一订单进入同一分区，消费顺序可控。
 */
@Configuration
public class KafkaConfig {

    /** 秒杀下单 topic（3 分区，单机环境副本 1） */
    public static final String TOPIC_SECKILL_ORDER = "seckill-order";

    /** 秒杀下单消费组 */
    public static final String GROUP_SECKILL_ORDER = "seckill-order-group";

    /**
     * 缓存删除补偿 topic。
     * 用途：更新店铺后删缓存，如果那一次删除失败（Redis 抖动 / 网络闪断），
     * 靠这条消息再删一次，避免缓存里长期躺着旧数据。
     */
    public static final String TOPIC_SHOP_CACHE_INVALIDATE = "shop-cache-invalidate";

    public static final String GROUP_SHOP_CACHE_INVALIDATE = "shop-cache-invalidate-group";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 应用启动时自动创建 topic，避免手工在命令行创建。
     * Spring Kafka 的 KafkaAdmin 会扫描容器中的 NewTopic bean 并自动建 topic。
     */
    @Bean
    public NewTopic seckillOrderTopic() {
        return new NewTopic(TOPIC_SECKILL_ORDER, 3, (short) 1);
    }

    /**
     * 生产者：key、value 均使用 String 序列化，消息体为订单 JSON。
     */
    @Bean
    public NewTopic shopCacheInvalidateTopic() {
        return new NewTopic(TOPIC_SHOP_CACHE_INVALIDATE, 1, (short) 1);
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // 发送失败重试，避免网络抖动导致订单消息丢失
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
