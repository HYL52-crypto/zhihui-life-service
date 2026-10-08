package com.hmdp.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.KafkaConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;

/**
 * 秒杀下单消费者：从 Kafka 拉取订单消息，落库并扣减库存。
 *
 * <p>三个关键点（面试高频）：
 * <ol>
 *     <li>幂等：按订单 ID 去重。Kafka 只保证「至少一次」投递，重复消费不能产生脏数据；</li>
 *     <li>事务：订单落库与库存扣减放在同一事务，避免「有订单但没扣库存」；</li>
 *     <li>手动提交 offset：处理成功才 ack，失败不提交，消息不会被静默丢弃。</li>
 * </ol>
 */
@Component
@Slf4j
public class SeckillVoucherListener {

    private final TransactionTemplate transactionTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ObjectMapper objectMapper;

    public SeckillVoucherListener(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_SECKILL_ORDER, groupId = KafkaConfig.GROUP_SECKILL_ORDER)
    public void onSeckillOrder(String message, Acknowledgment ack) {
        try {
            VoucherOrder voucherOrder = objectMapper.readValue(message, VoucherOrder.class);
            Long voucherId = voucherOrder.getVoucherId();

            // 幂等校验 + 落库与扣减库存在同一事务
            Boolean duplicated = transactionTemplate.execute(status -> {
                if (voucherOrderService.getById(voucherOrder.getId()) != null) {
                    return true;
                }
                voucherOrderService.save(voucherOrder);
                boolean success = seckillVoucherService.update()
                        .setSql("stock = stock - 1") // set stock = stock - 1
                        .eq("voucher_id", voucherId).gt("stock", 0) // where voucher_id = ? and stock > 0
                        .update();
                if (!success) {
                    log.warn("库存扣减失败，可能已无库存，voucherId: {}", voucherId);
                }
                return false;
            });

            if (Boolean.TRUE.equals(duplicated)) {
                log.info("订单已存在，跳过重复处理，orderId: {}", voucherOrder.getId());
            }
            // 处理成功才提交 offset
            ack.acknowledge();
        } catch (Exception e) {
            // 不 ack：offset 不提交，消息可被再次消费；同时记录日志便于排查毒消息
            log.error("消费秒杀订单消息失败，消息内容: {}", message, e);
        }
    }
}
