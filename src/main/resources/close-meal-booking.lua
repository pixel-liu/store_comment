redis.replicate_commands()
local clock = redis.call('time')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local bookingEnd = tonumber(ARGV[1])
if now < bookingEnd then
    -- Keep existing stock/receipt metadata, and block even a racing first publication.
    redis.call('hset', KEYS[1], 'booking_closed', '1')
    if redis.call('pttl', KEYS[1]) < 0 then
        redis.call('pexpireat', KEYS[1], bookingEnd)
    end
end
return 1
