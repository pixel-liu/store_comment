-- The database failure ledger is committed before invoking this script.
-- Keep the per-order marker so a duplicate DLQ delivery never releases a later reservation.
if redis.call('exists', KEYS[3]) == 1 then
    return 0
end
if redis.call('srem', KEYS[2], ARGV[1]) == 1 then
    -- An expired activity must not be resurrected by delayed compensation.
    if redis.call('exists', KEYS[1]) == 1 then
        redis.call('incr', KEYS[1])
    end
end
redis.call('set', KEYS[3], '1')
redis.call('lpush', KEYS[4], ARGV[2])
return 1
