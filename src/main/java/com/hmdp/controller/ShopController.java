package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     *
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) throws InterruptedException {
        return shopService.queryById(id);
    }

    /**
     * 按店铺名称模糊搜索。
     * 测试：GET /shop/search?keyword=茶
     * 注意：/shop/search 比 /shop/{id} 更精确，Spring 会优先匹配到这里，不会冲突。
     */
    @GetMapping("/search")
    public Result searchShop(@RequestParam("keyword") String keyword) {
        return shopService.searchByName(keyword);
    }

    /**
     * 缓存预热：把店铺数据以「逻辑过期」形式写入 Redis。
     * 逻辑过期的 key 永不过期，冷启动时缓存为空会直接查不到，所以必须先预热。
     * 测试：GET /shop/warm/1?seconds=1800  然后再访问 GET /shop/1
     *
     * @param id      店铺id
     * @param seconds 逻辑过期秒数，默认 1800（30 分钟）
     */
    @GetMapping("/warm/{id}")
    public Result warmShopCache(@PathVariable("id") Long id,
                                @RequestParam(value = "seconds", defaultValue = "1800") Long seconds) {
        shopService.saveShop2Redis(id, seconds);
        return Result.ok("预热完成，逻辑过期时间 " + seconds + " 秒");
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库

        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x",required = false) Double x,
            @RequestParam(value = "y",required = false) Double y
    ) {
        return shopService.queryShopByType(typeId,current,x,y);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
