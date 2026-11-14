redis.replicate_commands()
local clock = redis.call('time')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now >= tonumber(ARGV[1]) or redis.call('exists', KEYS[3]) == 1 then
    return 0
end
if redis.call('hget', KEYS[2], 'booking_closed') == '1' then
    return 0
end
local ttl = redis.call('pttl', KEYS[1])
if redis.call('hget', KEYS[2], 'meal_batch') ~= '1' or ttl <= 0 then
    return -1
end
-- Keep the original one-per-user marker after a confirmed reservation is cancelled.
redis.call('incr', KEYS[1])
redis.call('set', KEYS[3], '1', 'PX', ttl)
return 1
