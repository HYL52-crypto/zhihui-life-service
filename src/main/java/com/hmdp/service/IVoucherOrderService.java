package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 关闭超时未支付的订单，并回滚秒杀库存。
     *
     * @param minutes 超时分钟数
     * @return 实际关闭的订单数
     */
    int closeTimeoutOrders(int minutes);

    /**
     * 支付订单。用乐观锁保证「支付」和「自动关单」并发时只有一个能成功。
     *
     * @param orderId 订单id
     */
    Result payOrder(Long orderId);
}
