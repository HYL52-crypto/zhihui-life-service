package com.hmdp.annotation;

/**
 * 限流维度。
 */
public enum LimitType {
    /**
     * 全局限流：所有请求共用一个计数器，保护系统整体不被打垮
     */
    GLOBAL,

    /**
     * IP 维度：同一个 IP 单独计数，防爬虫、防刷接口
     */
    IP,

    /**
     * 用户维度：同一个登录用户单独计数，防刷券、防恶意下单
     */
    USER
}
