-- 滑动窗口限流（ZSet 实现）
-- KEYS[1] = 限流 key
-- ARGV[1] = 窗口大小（秒）
-- ARGV[2] = 窗口内允许的最大请求数
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = 本次请求的唯一标识（member）
-- 返回 1 = 放行，0 = 被限流

local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local member = ARGV[4]

-- 1. 移除滑出窗口的历史记录：只保留 [now - window*1000, now] 区间内的
redis.call('zremrangebyscore', key, 0, now - window * 1000)

-- 2. 统计当前窗口内已通过的请求数
local count = redis.call('zcard', key)

-- 3. 未达阈值：写入本次请求的时间戳，放行
if count < limit then
    redis.call('zadd', key, now, member)
    -- 4. 给 key 设过期时间，避免长期无人访问后仍占用内存
    redis.call('pexpire', key, window * 1000)
    return 1
end

-- 已达阈值：拒绝
return 0
