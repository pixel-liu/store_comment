-- Validate activity time with the Redis clock before reserving stock.
redis.replicate_commands()
local userId = ARGV[2]

local stockKey = KEYS[1]
local infoKey = KEYS[2]
local orderKey = KEYS[3]

local beginTime = tonumber(redis.call('hget', infoKey, 'begin_time'))
local endTime = tonumber(redis.call('hget', infoKey, 'end_time'))
if not beginTime or not endTime then
    return 3
end
local clock = redis.call('time')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now < beginTime then
    return 4
end
if now >= endTime then
    return 5
end

-- 先检查重复下单（SISMEMBER 比 DECR 更轻量）
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- A missing stock key must not be created by a failed reservation.
local stock = tonumber(redis.call('get', stockKey))
if not stock then
    return 3
end
if (stock <= 0) then
    return 1
end

-- 标记用户已下单
redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)
local ttl = redis.call('pttl', stockKey)
if ttl > 0 then
    redis.call('pexpire', orderKey, ttl)
end
return 0
