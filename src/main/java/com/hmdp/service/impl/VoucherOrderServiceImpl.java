package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 订单服务：秒杀下单使用 Lua 校验库存与一人一单，
 * 校验通过后异步发送到 Kafka 由消费者创建订单、扣减库存。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 订单状态：未支付 */
    private static final int STATUS_UNPAID = 1;
    /** 订单状态：已支付 */
    private static final int STATUS_PAID = 2;
    /** 订单状态：已取消 */
    private static final int STATUS_CANCELLED = 4;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒杀校验脚本：判断库存、一人一单；通过后扣减 Redis 库存并记录下单人。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 应用启动时批量预热秒杀优惠券库存到 Redis。
     * 仅当缓存 key 不存在时才写入，避免覆盖 Redis 中已扣减的实时库存。
     */
    @PostConstruct
    public void preloadSeckillVouchers() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers == null || vouchers.isEmpty()) {
            log.info("秒杀优惠券缓存预热完成，数据库中没有秒杀优惠券数据");
            return;
        }
        int loaded = 0;
        for (SeckillVoucher voucher : vouchers) {
            String key = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
            Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(key, voucher.getStock().toString());
            if (Boolean.TRUE.equals(set)) {
                loaded++;
            }
        }
        log.info("秒杀优惠券缓存预热完成，共 {} 个，实际写入 {} 个", vouchers.size(), loaded);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Result timeCheck = checkTimeWindow(voucherId);
        if (timeCheck != null) {
            return timeCheck;
        }
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是否为0
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            if (r == 1) {
                return Result.fail("库存不足");
            }
            if (r == 2) {
                return Result.fail("不能重复下单");
            }
            return Result.fail("秒杀失败");
        }

        // 3.异步下单：发消息给 Kafka（消息体为订单 JSON，由消费者落库并扣减库存）
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        try {
            // 以订单号作为 key：同一订单进入同一分区，消费顺序可控
            kafkaTemplate.send(KafkaConfig.TOPIC_SECKILL_ORDER, String.valueOf(orderId),
                    objectMapper.writeValueAsString(order));
        } catch (Exception e) {
            log.error("发送 Kafka 消息失败，订单ID: {}", orderId, e);
            throw new RuntimeException("发送消息失败");
        }
        // 4.返回订单号给前端（实际下单异步处理）
        return Result.ok(orderId);
    }

    /**
     * 关闭超时未支付订单并回滚库存。
     *
     * <p>并发安全靠乐观锁：UPDATE ... WHERE id = ? AND status = 1。
     * 如果此时用户正好支付成功，status 已变成 2，这条 UPDATE 影响行数为 0，关单自动放弃。
     */
    @Override
    public int closeTimeoutOrders(int minutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutes);
        List<VoucherOrder> timeoutOrders = lambdaQuery()
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .lt(VoucherOrder::getCreateTime, deadline)
                .list();
        if (timeoutOrders == null || timeoutOrders.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (VoucherOrder order : timeoutOrders) {
            boolean success = lambdaUpdate()
                    .set(VoucherOrder::getStatus, STATUS_CANCELLED)
                    .eq(VoucherOrder::getId, order.getId())
                    .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                    .update();
            if (!success) {
                // 影响行数为 0：订单已被支付或已关闭，交给对方处理
                log.info("订单 {} 状态已变更，跳过关单", order.getId());
                continue;
            }
            restoreStock(order.getVoucherId(), order.getUserId());
            closed++;
        }
        return closed;
    }

    /**
     * 支付订单。
     *
     * <p>乐观锁：WHERE status = 1。如果定时任务已经把订单关成 4，
     * 这条 UPDATE 影响行数为 0，支付失败，不会出现「关单后又支付成功」的脏数据。
     */
    @Override
    public Result payOrder(Long orderId) {
        boolean success = lambdaUpdate()
                .set(VoucherOrder::getStatus, STATUS_PAID)
                .set(VoucherOrder::getPayTime, LocalDateTime.now())
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .update();
        if (!success) {
            return Result.fail("订单状态已变更，支付失败（可能已被自动关闭）");
        }
        return Result.ok("支付成功");
    }

    /**
     * 回滚库存：Redis 库存 +1、移除一人一单标记、数据库库存 +1。
     */
    private void restoreStock(Long voucherId, Long userId) {
        // 1. Redis 库存回滚
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        // 2. 从已购集合中移除，允许用户重新下单（已取消的订单不应占用一人一单名额）
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString());
        // 3. 数据库库存回滚
        seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock + 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .update();
    }

    /**
     * 校验秒杀是否在有效时间窗内。
     * @return 时间窗不合法时返回错误 Result，合法时返回 null
     */
    private Result checkTimeWindow(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime() != null && now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime() != null && now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已结束");
        }
        return null;
    }
}