package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {
    Result queryById(Long id) throws InterruptedException;

    Result update(Shop shop);

    /**
     * 缓存预热：把指定店铺连同逻辑过期时间一起写入 Redis。
     * 逻辑过期方案下 key 永不过期，若不预热则首次查询必然查不到。
     *
     * @param id            店铺id
     * @param expireSeconds 逻辑过期时间（秒）
     */
    void saveShop2Redis(Long id, Long expireSeconds);

    /**
     * 按店铺名称模糊搜索（第 1 课练习：走完整的 Controller → Service → Mapper → MySQL 链路）
     *
     * @param keyword 店铺名关键词
     * @return 命中的店铺列表
     */
    Result searchByName(String keyword);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
