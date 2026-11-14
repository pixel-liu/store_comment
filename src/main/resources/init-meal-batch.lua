redis.replicate_commands()
local stockKey = KEYS[1]
local infoKey = KEYS[2]
local clock = redis.call('time')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now >= tonumber(ARGV[3]) or redis.call('hget', infoKey, 'booking_closed') == '1' then
    return 0
end
if redis.call('hget', infoKey, 'meal_batch') == '1' then
    return 1
end
redis.call('set', stockKey, ARGV[1])
redis.call('hmset', infoKey, 'begin_time', ARGV[2], 'end_time', ARGV[3],
    'meal_batch', '1', 'receipt_expires_at', ARGV[5])
redis.call('pexpireat', stockKey, ARGV[4])
redis.call('pexpireat', infoKey, ARGV[4])
return 1
