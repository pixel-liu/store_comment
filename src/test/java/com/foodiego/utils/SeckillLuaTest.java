package com.foodiego.utils;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Run against a dedicated Redis instance; never flushes a shared database. */
@EnabledIfEnvironmentVariable(named = "FOODIEGO_TEST_REDIS_PORT", matches = "[0-9]+")
class SeckillLuaTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private List<String> keys;
    private String marker;
    private String log;
    private final DefaultRedisScript<Long> reserve = script("seckill2.lua");
    private final DefaultRedisScript<Long> init = script("init-seckill.lua");
    private final DefaultRedisScript<Long> compensate = script("compensate-seckill.lua");

    private DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name));
        script.setResultType(Long.class);
        return script;
    }

    @BeforeEach void setup() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("FOODIEGO_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        String prefix = "test:correctness:" + UUID.randomUUID() + ":";
        keys = Arrays.asList(prefix+"stock", prefix+"info", prefix+"orders");
        marker = prefix+"compensated"; log = prefix+"dead";
    }
    @AfterEach void cleanup() {
        if (redis != null) {
            redis.delete(keys); redis.delete(Arrays.asList(marker,log));
        }
        if (factory != null) factory.destroy();
    }
    private void publish(long begin, long end) {
        assertEquals(1L, redis.execute(init, keys.subList(0,2), "2", Long.toString(begin),
                Long.toString(end), Long.toString(end + TimeUnit.DAYS.toMillis(1))));
    }
    private Long buy(String user) { return redis.execute(reserve, keys, "100", user); }

    @Test void missingActivityDoesNotCreateStock() {
        assertEquals(3L, buy("1")); assertFalse(redis.hasKey(keys.get(0)));
    }
    @Test void futureActivityRejectsBeforeChangingStock() {
        long now = System.currentTimeMillis(); publish(now+60000, now+120000);
        assertEquals(4L, buy("1")); assertEquals("2", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(2)));
    }
    @Test void endedActivityRejectsBeforeChangingStock() {
        long now = System.currentTimeMillis(); publish(now-120000, now-60000);
        assertEquals(5L, buy("1")); assertEquals("2", redis.opsForValue().get(keys.get(0)));
    }
    @Test void successfulPurchaseDeduplicatesAndDoesNotOversell() {
        long now = System.currentTimeMillis(); publish(now-60000, now+3600000);
        assertEquals(0L, buy("1")); assertEquals(2L, buy("1"));
        assertEquals(0L, buy("2")); assertEquals(1L, buy("3"));
        assertEquals("0", redis.opsForValue().get(keys.get(0)));
        assertTrue(redis.getExpire(keys.get(2)) > 0);
    }
    @Test void ttlCoversWholeActivityEvenWhenMinuteSecondFieldsAreEqual() {
        long now = System.currentTimeMillis(); publish(now-1000, now+3600000);
        assertTrue(redis.getExpire(keys.get(0)) > 3600);
        assertTrue(redis.getExpire(keys.get(1)) > 3600);
    }
    @Test void missingStockIsRejectedWithoutCreatingNegativeKey() {
        long now = System.currentTimeMillis(); publish(now-1000, now+3600000);
        redis.delete(keys.get(0)); assertEquals(3L, buy("1"));
        assertFalse(redis.hasKey(keys.get(0)));
    }
    @Test void duplicateCompensationCannotReleaseANewerReservation() {
        long now = System.currentTimeMillis(); publish(now-1000, now+3600000);
        assertEquals(0L, buy("1"));
        List<String> compensationKeys = Arrays.asList(keys.get(0),keys.get(2),marker,log);
        assertEquals(1L, redis.execute(compensate, compensationKeys, "1", "1:100:1"));
        assertEquals("2", redis.opsForValue().get(keys.get(0)));
        assertEquals(0L, buy("1"));
        assertEquals(0L, redis.execute(compensate, compensationKeys, "1", "1:100:1"));
        assertEquals("1", redis.opsForValue().get(keys.get(0)));
        assertTrue(redis.opsForSet().isMember(keys.get(2), "1"));
        assertEquals(1L, redis.opsForList().size(log));
    }
    @Test void delayedCompensationNeverResurrectsExpiredStock() {
        redis.opsForSet().add(keys.get(2), "1");
        assertEquals(1L, redis.execute(compensate,
                Arrays.asList(keys.get(0),keys.get(2),marker,log), "1", "1:100:1"));
        assertFalse(redis.hasKey(keys.get(0)));
    }
}
