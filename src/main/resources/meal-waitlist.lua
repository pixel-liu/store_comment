redis.replicate_commands()
local stockKey, infoKey, ordersKey, queueKey, holdsKey, recordsKey, sequenceKey, activeKey, userKey = unpack(KEYS)
local action, batch, user, attempt = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
local holdMillis = tonumber(ARGV[5])
local clock = redis.call('time')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local start = tonumber(redis.call('hget', infoKey, 'begin_time'))
local deadline = tonumber(redis.call('hget', infoKey, 'end_time'))
local stock = tonumber(redis.call('get', stockKey))
local open = start and deadline and stock and now >= start and now < deadline
    and redis.call('hget', infoKey, 'meal_batch') == '1'
    and redis.call('hget', infoKey, 'booking_closed') ~= '1'

local function status()
    local record = redis.call('hget', recordsKey, user)
    if not record then return 'MISSING' end
    local version, state, order = string.match(record, '^(%d+):([A-Z]+):?(%d*)$')
    if state == 'CONFIRMED' or state == 'FAILED' then return state .. ':' .. version .. ':' .. order end
    if state == 'WAITING' or state == 'OFFERED' then
        if not open then return 'CLOSED:' .. version end
        if state == 'OFFERED' then
            local expires = tonumber(redis.call('zscore', holdsKey, user))
            if not expires or expires <= now then return 'EXPIRED:' .. version end
            return 'OFFERED:' .. version .. ':' .. string.format('%.0f', expires)
        end
        local rank = redis.call('zrank', queueKey, user)
        if rank then return 'WAITING:' .. version .. ':' .. (rank + 1) end
    end
    return state .. ':' .. version
end

if action == 'status' then return status() end
if action == 'join' then
    if not open then return 'CLOSED' end
    if redis.call('sismember', ordersKey, user) == 1 then return 'ALREADY_BOOKED' end
    local current = status()
    if string.match(current, '^WAITING:') or string.match(current, '^OFFERED:') then return current end
    if string.match(current, '^CONFIRMED:') then return current end
    -- A logically expired hold is still deducted until the worker releases it.
    if redis.call('zscore', holdsKey, user) then return 'RELEASING' end
    if stock > 0 and redis.call('zcard', queueKey) == 0 then return 'AVAILABLE' end
    local version = redis.call('incr', sequenceKey)
    redis.call('hset', recordsKey, user, version .. ':WAITING')
    redis.call('zadd', queueKey, version, user)
    local retention = deadline + 86400000
    for _, key in ipairs({queueKey, recordsKey, sequenceKey}) do
        redis.call('pexpireat', key, retention)
    end
    redis.call('zremrangebyscore', userKey, '-inf', now)
    redis.call('zadd', userKey, retention, batch)
    if redis.call('pttl', userKey) < retention - now then redis.call('pexpireat', userKey, retention) end
    redis.call('zadd', activeKey, now, batch)
    return status()
end

if action == 'leave' then
    local record = redis.call('hget', recordsKey, user)
    if record == attempt .. ':LEFT' then return 'LEFT:' .. attempt end
    if not open or (record ~= attempt .. ':WAITING' and record ~= attempt .. ':OFFERED') then return 'STALE' end
    redis.call('zrem', queueKey, user)
    if redis.call('zrem', holdsKey, user) == 1 then redis.call('incr', stockKey) end
    redis.call('hset', recordsKey, user, attempt .. ':LEFT')
    redis.call('zadd', activeKey, now, batch)
    return 'LEFT:' .. attempt
end

if not open then
    redis.call('zrem', activeKey, batch)
    return 'CLOSED'
end

local due = tonumber(redis.call('zscore', activeKey, batch))
if not due or due > now then return 'OK' end

-- Release expired offers before handing free portions to the head of the queue.
local expired = redis.call('zrangebyscore', holdsKey, '-inf', now, 'LIMIT', 0, 50)
for _, member in ipairs(expired) do
    local record = redis.call('hget', recordsKey, member)
    redis.call('hset', recordsKey, member, (string.gsub(record, 'OFFERED$', 'EXPIRED')))
    redis.call('zrem', holdsKey, member)
    stock = redis.call('incr', stockKey)
end
if stock > 0 then
    for _, member in ipairs(redis.call('zrange', queueKey, 0, math.min(stock, 50) - 1)) do
        local record = redis.call('hget', recordsKey, member)
        redis.call('zrem', queueKey, member)
        if redis.call('sismember', ordersKey, member) == 0 then
            redis.call('decr', stockKey)
            redis.call('hset', recordsKey, member, (string.gsub(record, 'WAITING$', 'OFFERED')))
            redis.call('zadd', holdsKey, math.min(deadline, now + holdMillis), member)
            redis.call('pexpireat', holdsKey, deadline + 86400000)
        else
            redis.call('hset', recordsKey, member, (string.gsub(record, 'WAITING$', 'LEFT')))
        end
    end
end
if redis.call('zcard', queueKey) == 0 and redis.call('zcard', holdsKey) == 0 then
    redis.call('zrem', activeKey, batch)
else
    redis.call('zadd', activeKey, now + 1000, batch)
end
return 'OK'
