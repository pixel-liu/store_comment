-- Publish stock and metadata together, retaining them one day after the end.
-- Eligibility is enforced separately by seckill2.lua using the Redis clock.
redis.call('set', KEYS[1], ARGV[1])
redis.call('hmset', KEYS[2], 'begin_time', ARGV[2], 'end_time', ARGV[3])
redis.call('pexpireat', KEYS[1], ARGV[4])
redis.call('pexpireat', KEYS[2], ARGV[4])
return 1
