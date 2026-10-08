package com.hmdp.controller;


import com.hmdp.annotation.LimitType;
import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀下单。
     * 限流：同一用户 10 秒内最多 5 次，防止脚本刷券。
     */
    @RateLimit(type = LimitType.USER, window = 10, limit = 5, message = "操作过于频繁，请稍后再试")
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 支付订单。内部用乐观锁保证与自动关单并发时只有一个生效。
     */
    @PostMapping("pay/{id}")
    public Result payOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }

    /**
     * 手动触发超时关单（方便验证，生产上由 OrderCloseTask 每分钟自动执行）。
     * 测试：POST /voucher-order/close-timeout?minutes=0  → 立刻关闭所有未支付订单
     */
    @PostMapping("close-timeout")
    public Result closeTimeoutOrders(@RequestParam(value = "minutes", defaultValue = "30") int minutes) {
        int count = voucherOrderService.closeTimeoutOrders(minutes);
        return Result.ok("已关闭超时订单 " + count + " 笔");
    }
}
