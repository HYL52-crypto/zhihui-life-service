package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.KafkaConfig;
import com.hmdp.dto.CacheInvalidateMessage;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient clientClient;

    @Resource
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 本地缓存（Caffeine），与 Redis 组成二级缓存
     */
    @Resource
    private com.github.benmanes.caffeine.cache.Cache<Long, Shop> shopLocalCache;

    @Override
    public Result queryById(Long id) {
        // ① 一级缓存：Caffeine 本地缓存，命中直接返回，连 Redis 都不访问
        Shop shop = shopLocalCache.getIfPresent(id);
        if (shop != null) {
            return Result.ok(shop);
        }
        // ② 二级缓存：Redis，用逻辑过期解决缓存击穿
        shop = clientClient.
                queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // ③ Redis 也没有（首次访问 / TTL 兜底把 key 清掉了）→ 回源数据库并回填
        //    没有这一步，key 一旦消失接口就直接报"店铺不存在"，属于可用性缺陷
        if (shop == null) {
            shop = getById(id);
            if (shop == null) {
                // 数据库里确实没有：缓存空值防穿透
                stringRedisTemplate.opsForValue()
                        .set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在！");
            }
            writeShopCache(shop, CACHE_SHOP_TTL * 60);
        }
        // ④ 回种本地缓存，下一次同样的查询直接走内存
        shopLocalCache.put(id, shop);
        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1.查询店铺数据
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }
        // 2.写缓存（内部封装逻辑过期 + 真实 TTL 兜底）
        writeShopCache(shop, expireSeconds);
    }

    /**
     * 写店铺缓存：逻辑过期 + 真实 TTL 兜底。
     * <p>
     * 逻辑过期的 key 本身是不设过期时间的（永不过期，靠后台线程重建），
     * 这会带来一个隐患：万一更新店铺时删缓存那一步失败，这条脏数据会永远躺在 Redis 里。
     * 所以这里额外给一个「真实 TTL = 逻辑过期时间 + 5~15 分钟随机抖动」：
     * <ul>
     *   <li>正常情况：逻辑过期先到，触发异步重建，用户感知不到；</li>
     *   <li>异常情况：真实 TTL 到期，key 自动消失，最坏也就是多查一次库，不会一直脏。</li>
     * </ul>
     * 随机抖动的目的是避免同一批 key 同时到期造成缓存雪崩。
     */
    private void writeShopCache(Shop shop, Long logicalSeconds) {
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalSeconds));
        long realTtl = logicalSeconds + 300 + ThreadLocalRandom.current().nextInt(600);
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisData), realTtl, TimeUnit.SECONDS);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.先修改数据库（Cache Aside 的正确顺序：先改库，再删缓存）
        updateById(shop);

        // 2.删 Redis 缓存，并且校验是否真删掉了
        boolean deleted = deleteShopCache(id);

        // 3.无论删没删成功，都发一条 MQ 做「延迟双删」补偿。
        //   为什么删成功了还要发：删除与「其他请求正在回源旧数据」存在并发窗口，
        //   晚一步再删一次可以把这个窗口里写进去的旧数据清掉。
        sendInvalidateMessage(id, deleted ? "double-delete" : "first-delete-failed");

        // 4.删除本地缓存，否则本实例接下来 60 秒会一直返回旧数据
        shopLocalCache.invalidate(id);
        return Result.ok();
    }

    /** 删缓存并校验结果，失败不抛异常（缓存删除失败不应该让更新接口报错） */
    private boolean deleteShopCache(Long id) {
        try {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            Boolean exists = stringRedisTemplate.hasKey(CACHE_SHOP_KEY + id);
            return exists == null || !exists;
        } catch (Exception e) {
            log.error("删除店铺缓存异常, shopId={}", id, e);
            return false;
        }
    }

    /** 发 Kafka 补偿消息；发消息失败只记日志，不能影响主流程 */
    private void sendInvalidateMessage(Long id, String reason) {
        try {
            CacheInvalidateMessage msg = new CacheInvalidateMessage(id, 0, reason);
            kafkaTemplate.send(KafkaConfig.TOPIC_SHOP_CACHE_INVALIDATE, String.valueOf(id),
                    objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.error("发送缓存失效补偿消息失败, shopId={}", id, e);
        }
    }

    /**
     * 按店铺名称模糊搜索。
     * query() 来自 MyBatis-Plus 的 ServiceImpl，等价于一条 SELECT * FROM tb_shop WHERE name LIKE ?
     */
    @Override
    public Result searchByName(String keyword) {
        // 1.参数校验：空关键词直接挡掉，避免全表扫描
        if (keyword == null || keyword.trim().isEmpty()) {
            return Result.fail("搜索关键词不能为空");
        }
        // 2.模糊查询：like("name", keyword) → SQL: where name like '%keyword%'
        List<Shop> shops = query().like("name", keyword).list();
        // 3.统一返回
        return Result.ok(shops);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.是否根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，走数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis，按照距离排序，分页。结果：shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // 4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        // 5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}