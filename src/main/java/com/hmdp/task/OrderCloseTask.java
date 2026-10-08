package com.hmdp.task;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 未支付订单自动关闭任务。
 *
 * <p>为什么用定时任务扫表，而不是延迟消息？
 * 延迟消息（如 RabbitMQ 死信 / Kafka 时间轮）实时性更好，但消息可能丢失、堆积；
 * 定时扫表实现简单、可自愈（漏掉的下一轮还能扫到），代价是有延迟且要给 create_time 建索引。
 * 这里两者都保留：定时任务兜底，保证最终一致。
 */
@Slf4j
@Component
public class OrderCloseTask {

    /**
     * 订单超时时间（分钟）：超过这个时间仍未支付就自动关闭
     */
    private static final int TIMEOUT_MINUTES = 30;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 每分钟执行一次：关闭超时未支付订单并回滚库存。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void closeTimeoutOrders() {
        try {
            int count = voucherOrderService.closeTimeoutOrders(TIMEOUT_MINUTES);
            if (count > 0) {
                log.info("定时关单完成，关闭超时订单 {} 笔", count);
            }
        } catch (Exception e) {
            // 定时任务里必须吞掉异常，否则一次失败会导致后续所有调度中断
            log.error("定时关单执行失败", e);
        }
    }
}
