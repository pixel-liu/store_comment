-- Validate activity time with the Redis clock before reserving stock.
redis.replicate_commands()
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = KEYS[1]
local infoKey = KEYS[2]
local orderKey = KEYS[3]
local outboxKey = KEYS[4]
local receiptKey = KEYS[5]
local queueKey, holdsKey, recordsKey = KEYS[6], KEYS[7], KEYS[8]
local attempt = ARGV[4]
local waitRecord
if recordsKey then
    waitRecord = redis.call('hget', recordsKey, userId)
end
if attempt and waitRecord then
    local version, state = string.match(waitRecord, '^(%d+):([A-Z]+):%d+$')
    if version == attempt and (state == 'CONFIRMED' or state == 'FAILED') then return 8 end
end

local beginTime = tonumber(redis.call('hget', infoKey, 'begin_time'))
local endTime = tonumber(redis.call('hget', infoKey, 'end_time'))
if not beginTime or not endTime then
    return 3
end
local mealBatch = redis.call('hget', infoKey, 'meal_batch') == '1'
if mealBatch ~= (receiptKey ~= nil) then
    return 6
end
if mealBatch and redis.call('hget', infoKey, 'booking_closed') == '1' then
    return 5
end
local receiptExpiry = mealBatch and tonumber(redis.call('hget', infoKey, 'receipt_expires_at'))
if mealBatch and not receiptExpiry then
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
if attempt then
    local expires = tonumber(redis.call('zscore', holdsKey, userId))
    if waitRecord ~= attempt .. ':OFFERED' or not expires or expires <= now then return 9 end
    redis.call('zrem', holdsKey, userId)
    redis.call('hset', recordsKey, userId, attempt .. ':CONFIRMED:' .. orderId)
else
    if stock <= 0 then return 1 end
    if queueKey and (redis.call('zcard', queueKey) > 0 or redis.call('zscore', holdsKey, userId)) then return 7 end
    redis.call('decr', stockKey)
end

-- 标记用户已下单
redis.call('sadd', orderKey, userId)
local ttl = redis.call('pttl', stockKey)
if ttl > 0 then
    redis.call('pexpire', orderKey, ttl)
end
local event = orderId .. ':' .. voucherId .. ':' .. userId
if mealBatch then
    redis.call('set', receiptKey, userId .. ':' .. voucherId)
    redis.call('pexpireat', receiptKey, receiptExpiry)
    event = event .. ':meal'
end
-- Write the event in the same script as the inventory reservation.
redis.call('lpush', outboxKey, event)
return 0
