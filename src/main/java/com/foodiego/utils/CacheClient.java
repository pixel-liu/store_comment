package com.foodiego.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.foodiego.entity.RedisLogicalData;
import com.foodiego.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.function.Function;
import java.util.UUID;

import static com.foodiego.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 缓存雪崩防护：在 TTL 基础上添加随机偏移（最多 20%），避免大量 key 同时过期
        long actualTime = time + ThreadLocalRandom.current().nextLong(time / 5 + 1);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), actualTime, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 缓存雪崩防护：在逻辑过期时间上添加随机偏移（最多 20%），避免大量 key 同时逻辑过期
        long actualSeconds = unit.toSeconds(time) + ThreadLocalRandom.current().nextLong(time / 5 + 1);
        RedisLogicalData redisLogicalData = new RedisLogicalData();
        redisLogicalData.setExpireTime(LocalDateTime.now().plusSeconds(actualSeconds));
        redisLogicalData.setData(value);
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisLogicalData));
    }

    // 缓存穿透
    public <R, ID> R queryPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 根据id查询Redis中商铺数据
        String shopKey = keyPrefix + id;
        String jsonShop = stringRedisTemplate.opsForValue().get(shopKey);

        // 存在，直接返回
        if (StrUtil.isNotBlank(jsonShop)) {
            return JSONUtil.toBean(jsonShop, type);
        }
        // 判断命中的值是否为空
        if (jsonShop != null) {
            return null;
        }

        // 不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 数据库不存在，返回空值
        if (r == null) {
            stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 将shop 数据写入Redis
        this.set(shopKey, r, time, unit);
        // 返回数据
        return r;
    }

    // Package-private non-final for testability — allows tests to inject a synchronous executor
    static ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    // 缓存击穿-逻辑过期
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 根据id查询Redis中商铺数据
        String shopKey = keyPrefix + id;
        // 1.获取当前用户
        String jsonShop = stringRedisTemplate.opsForValue().get(shopKey);

        // 未命中，直接返回
        if (StrUtil.isBlank(jsonShop)) {
            return null;
        }

        // json转为RedisLogicalData对象
        RedisLogicalData redisLogicalData = JSONUtil.toBean(jsonShop, RedisLogicalData.class);

        // json转为Shop对象
        String dataJson = JSONUtil.toJsonStr(redisLogicalData.getData());
        R r = JSONUtil.toBean(dataJson, type);

        LocalDateTime expireTime = redisLogicalData.getExpireTime();

        boolean flag = expireTime.isAfter(LocalDateTime.now());

        if (flag) {
            // 未过期，返回店铺信息
            return r;
        }

        // 缓存过期，尝试获取带所有者标识的锁。
        String lockKey = LOCK_SHOP_KEY + id;
        String lockToken = tryLock(lockKey);
        if (lockToken == null) {
            return r;
        }
        boolean rebuildHandedOff = false;
        try {
            // Double check: another request may have completed the rebuild before this lock was acquired.
            jsonShop = stringRedisTemplate.opsForValue().get(shopKey);
            if (jsonShop != null) {
                redisLogicalData = JSONUtil.toBean(jsonShop, RedisLogicalData.class);
                String jsonStr = JSONUtil.toJsonStr(redisLogicalData.getData());
                r = JSONUtil.toBean(jsonStr, type);
                if (redisLogicalData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return r;
                }
            }
            final String token = lockToken;
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallback.apply(id);
                    if (newR != null) {
                        this.setWithLogicalExpire(shopKey, newR, time, unit);
                    }
                } catch (Exception e) {
                    log.error("重建缓存失败", e);
                } finally {
                    unLock(lockKey, token);
                }
            });
            rebuildHandedOff = true;
            return r;
        } finally {
            // The async task owns the lock only after it has been submitted successfully.
            // Every early return/error before that point must release this caller's token.
            if (!rebuildHandedOff) {
                unLock(lockKey, lockToken);
            }
        }
    }

    public <R, ID> R queryWithMute(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 根据id查询Redis中商铺数据
        String shopKey = keyPrefix + id;
        String jsonShop = stringRedisTemplate.opsForValue().get(shopKey);
        // 存在，直接返回
        if (StrUtil.isNotBlank(jsonShop)) {
            return JSONUtil.toBean(jsonShop, type);
        }
        // 不存在，尝试获取锁；最多等待三次，避免递归占满请求线程。
        String lockKey = LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < 3; attempt++) {
            String lockToken = tryLock(lockKey);
            if (lockToken == null) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待缓存重建时被中断", e);
                }
                String retryJson = stringRedisTemplate.opsForValue().get(shopKey);
                if (StrUtil.isNotBlank(retryJson)) {
                    return JSONUtil.toBean(retryJson, type);
                }
                continue;
            }
            try {
                String retryJson = stringRedisTemplate.opsForValue().get(shopKey);
                if (StrUtil.isNotBlank(retryJson)) {
                    return JSONUtil.toBean(retryJson, type);
                }
                R r = dbFallback.apply(id);
                if (r == null) {
                    stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                this.set(shopKey, r, time, unit);
                return r;
            } finally {
                unLock(lockKey, lockToken);
            }
        }
        return null;
    }

    // 获取锁
    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? token : null;
    }

    // 释放锁
    private void unLock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }
}
