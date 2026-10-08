package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存失效补偿消息。
 * 更新店铺后如果删缓存那一步失败，就把这条消息发到 Kafka，由消费者再删一次。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidateMessage {

    /** 需要失效的店铺id */
    private Long shopId;

    /** 第几次补偿（从 0 开始），用于日志排查 */
    private int retry;

    /** 触发原因，方便排查：update 表示改了店铺 */
    private String reason;
}
