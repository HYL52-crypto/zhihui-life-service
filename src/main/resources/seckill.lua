-- 1.参数列表
--1.1.优惠券id
local voucherId=ARGV[1]
--1.2.用户id
local userId=ARGV[2]
--1.3.订单id
local orderId=ARGV[3]

-- 2.数据key
--2.1.库存key
local stockKey='seckill:stock:' .. voucherId
--2.2.订单key
local orderKey='seckill:order:' .. voucherId

-- 3.脚本业务
--3.1.判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    --print("库存获取失败: " .. stockKey)
    return -1
end

if (stock<= 0) then
    --3.2.库存不足，返回1
    return 1
end
--3.3.判断用户是否下单
if(redis.call('sismember',orderKey,userId)==1) then
    --3.4.存在，说明重复下单，返回2
    return 2
end
-- 3.5.扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.6.下单(保存)用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)
-- 3.7. Lua 只负责"资格校验 + 扣库存 + 记一人一单"，不做消息投递。
--      原版这里用 XADD 写 Redis Stream，本项目已改为 Kafka：
--      由 Java 侧拿到 0 之后再用 kafkaTemplate 投递订单消息。
--      把 MQ 留在 Java 侧的好处是：可以利用 Kafka 的分区、重试和消费者组，
--      而且 Redis 挂了不影响已通过校验的请求正常下单。
-- 3.8. 返回 0 表示秒杀资格校验通过，Java 侧据此发 Kafka 消息
return 0