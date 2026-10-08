package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 到店预约记录
 * <p>
 * 智能客服（LangChain4j Function Calling）里的「预约到店」工具会往这张表插数据，
 * 这样 AI 就不是空口白话，而是真的能调用业务接口产生一条业务数据。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_appointment")
public class Appointment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 预约人 */
    private Long userId;

    /** 预约的商家 */
    private Long shopId;

    /** 商家名称（冗余一份，列表展示不用再查 shop 表） */
    private String shopName;

    /** 到店时间，用户口述即可，例如 "今晚18点" */
    private String timeSlot;

    /** 到店人数 */
    private Integer peopleCount;

    /** 联系电话 */
    private String contactPhone;

    /** 状态：1-已预约 2-已到店 3-已取消 */
    private Integer status;

    private LocalDateTime createTime;
}
