package com.foodiego.utils;

import cn.hutool.json.JSONUtil;
import com.foodiego.entity.RedisLogicalData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CacheClient} — cache penetration, breakdown, and snowslide strategies.
 * Pure Mockito test: no Spring context needed.
 * <p>
 * Uses {@code Map.class} as the generic type because Hutool JSON serialization
 * round-trips correctly for Map objects (unlike plain Strings).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CacheClient Unit Tests")
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CacheClient cacheClient;

    // Synchronous executor for deterministic testing
    private ExecutorService syncExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        syncExecutor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(syncExecutor).submit(any(Runnable.class));
        ReflectionTestUtils.setField(CacheClient.class, "CACHE_REBUILD_EXECUTOR", syncExecutor);
    }

    private Map<String, Object> testData(String key, String value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    // ────────────────────── set() — snowslide prevention ──────────────────────

    @Test
    @DisplayName("set() adds random TTL jitter to prevent snowslide")
    void set_AddsRandomJitterToTtl() {
        Map<String, Object> data = testData("name", "test");
        cacheClient.set("test:key", data, 100L, TimeUnit.SECONDS);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(eq("test:key"), anyString(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));

        long actualTtl = ttlCaptor.getValue();
        assertTrue(actualTtl >= 100, "TTL should be >= base value, but was " + actualTtl);
        assertTrue(actualTtl <= 120, "TTL should be <= base + 20%, but was " + actualTtl);
    }

    @Test
    @DisplayName("setWithLogicalExpire() wraps data in RedisLogicalData envelope with jitter")
    void setWithLogicalExpire_WrapsDataInRedisLogicalData() {
        Map<String, Object> testData = testData("shop", "value");
        cacheClient.setWithLogicalExpire("test:key", testData, 30L, TimeUnit.MINUTES);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("test:key"), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertNotNull(json);
        RedisLogicalData logicalData = JSONUtil.toBean(json, RedisLogicalData.class);
        assertNotNull(logicalData.getData());
        assertNotNull(logicalData.getExpireTime());
        assertTrue(logicalData.getExpireTime().isAfter(LocalDateTime.now()),
                "Logical expire time should be in the future");
    }

    // ────────────────────── queryPassThrough() — penetration protection ──────────────────────

    @Test
    @DisplayName("queryPassThrough: cache hit returns cached map data directly")
    void queryPassThrough_CacheHit_ReturnsData() {
        Map<String, Object> cached = testData("key", "cached-value");
        String cachedJson = JSONUtil.toJsonStr(cached);
        when(valueOperations.get("cache:key:1")).thenReturn(cachedJson);

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryPassThrough("cache:key:", 1L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals("cached-value", result.get("key"));
        verify(dbFallback, never()).apply(anyLong());
    }

    @Test
    @DisplayName("queryPassThrough: empty-string cache hit returns null (penetration guard)")
    void queryPassThrough_CacheHitEmptyString_ReturnsNull() {
        when(valueOperations.get("cache:key:99")).thenReturn("");

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryPassThrough("cache:key:", 99L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertNull(result);
        verify(dbFallback, never()).apply(anyLong());
    }

    @Test
    @DisplayName("queryPassThrough: cache miss queries DB and caches result")
    void queryPassThrough_CacheMiss_QueriesDbAndCaches() {
        when(valueOperations.get("cache:key:1")).thenReturn(null);
        Map<String, Object> dbValue = testData("result", "db-value");
        Function<Long, Map> dbFallback = id -> dbValue;

        Map result = cacheClient.queryPassThrough("cache:key:", 1L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertEquals("db-value", result.get("result"));
        verify(valueOperations).set(eq("cache:key:1"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("queryPassThrough: DB returns null → caches empty string for penetration protection")
    void queryPassThrough_DbReturnsNull_CachesEmptyString() {
        when(valueOperations.get("cache:key:1")).thenReturn(null);
        Function<Long, Map> dbFallback = id -> null;

        Map result = cacheClient.queryPassThrough("cache:key:", 1L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertNull(result);
        verify(valueOperations).set(eq("cache:key:1"), eq(""), eq(2L), eq(TimeUnit.MINUTES));
    }

    // ────────────────────── queryWithLogicalExpire() — breakdown protection ──────────────────────

    @Test
    @DisplayName("queryWithLogicalExpire: not expired → returns data without rebuild")
    void queryWithLogicalExpire_NotExpired_ReturnsData() {
        Map<String, Object> data = testData("name", "shop-data");
        RedisLogicalData logicalData = new RedisLogicalData();
        logicalData.setData(data);
        logicalData.setExpireTime(LocalDateTime.now().plusHours(1));
        String cachedJson = JSONUtil.toJsonStr(logicalData);

        when(valueOperations.get("cache:key:1")).thenReturn(cachedJson);

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryWithLogicalExpire("cache:key:", 1L, Map.class,
                dbFallback, 30L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals("shop-data", result.get("name"));
        verify(dbFallback, never()).apply(anyLong());
    }

    @Test
    @DisplayName("queryWithLogicalExpire: expired + lock acquired → triggers background rebuild, returns stale")
    void queryWithLogicalExpire_Expired_LockAcquired_Rebuilds() {
        Map<String, Object> data = testData("name", "stale-data");
        RedisLogicalData logicalData = new RedisLogicalData();
        logicalData.setData(data);
        logicalData.setExpireTime(LocalDateTime.now().minusMinutes(1)); // expired
        String cachedJson = JSONUtil.toJsonStr(logicalData);

        when(valueOperations.get("cache:key:1")).thenReturn(cachedJson);
        when(valueOperations.setIfAbsent(eq("lock:shop:1"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        Map<String, Object> freshData = testData("name", "fresh-data");
        Function<Long, Map> dbFallback = id -> freshData;
        Map result = cacheClient.queryWithLogicalExpire("cache:key:", 1L, Map.class,
                dbFallback, 30L, TimeUnit.MINUTES);

        // Returns stale data while rebuild happens in background
        assertEquals("stale-data", result.get("name"));
        // Background executor (sync) should have rebuilt the cache
        verify(valueOperations, atLeastOnce()).set(eq("cache:key:1"), anyString());
        // Lock should be released after rebuild
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(java.util.Collections.singletonList("lock:shop:1")), anyString());
    }

    @Test
    @DisplayName("queryWithLogicalExpire: expired + lock failed → returns stale data immediately")
    void queryWithLogicalExpire_Expired_LockFailed_ReturnsStaleData() {
        Map<String, Object> data = testData("name", "stale-data");
        RedisLogicalData logicalData = new RedisLogicalData();
        logicalData.setData(data);
        logicalData.setExpireTime(LocalDateTime.now().minusMinutes(1));
        String cachedJson = JSONUtil.toJsonStr(logicalData);

        when(valueOperations.get("cache:key:1")).thenReturn(cachedJson);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(false);

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryWithLogicalExpire("cache:key:", 1L, Map.class,
                dbFallback, 30L, TimeUnit.MINUTES);

        assertEquals("stale-data", result.get("name"));
        verify(dbFallback, never()).apply(anyLong());
    }

    @Test
    @DisplayName("queryWithLogicalExpire: double-check — cache already rebuilt by another thread")
    void queryWithLogicalExpire_DoubleCheck_CacheAlreadyRebuilt() {
        Map<String, Object> staleData = testData("name", "stale-data");
        RedisLogicalData staleEntry = new RedisLogicalData();
        staleEntry.setData(staleData);
        staleEntry.setExpireTime(LocalDateTime.now().minusMinutes(1));

        Map<String, Object> freshData = testData("name", "fresh-data");
        RedisLogicalData freshEntry = new RedisLogicalData();
        freshEntry.setData(freshData);
        freshEntry.setExpireTime(LocalDateTime.now().plusHours(1));

        when(valueOperations.get("cache:key:1"))
                .thenReturn(JSONUtil.toJsonStr(staleEntry))
                .thenReturn(JSONUtil.toJsonStr(freshEntry));

        when(valueOperations.setIfAbsent(eq("lock:shop:1"), anyString(), anyLong(), any()))
                .thenReturn(true);

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryWithLogicalExpire("cache:key:", 1L, Map.class,
                dbFallback, 30L, TimeUnit.MINUTES);

        assertEquals("fresh-data", result.get("name"));
        verify(dbFallback, never()).apply(anyLong());
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(java.util.Collections.singletonList("lock:shop:1")), anyString());
    }

    // ────────────────────── queryWithMute() — mutex-based rebuild ──────────────────────

    @Test
    @DisplayName("queryWithMute: cache hit returns immediately")
    void queryWithMute_CacheHit_ReturnsData() {
        Map<String, Object> cached = testData("key", "mutex-value");
        String cachedJson = JSONUtil.toJsonStr(cached);
        when(valueOperations.get("cache:key:1")).thenReturn(cachedJson);

        Function<Long, Map> dbFallback = mock(Function.class);
        Map result = cacheClient.queryWithMute("cache:key:", 1L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals("mutex-value", result.get("key"));
        verify(dbFallback, never()).apply(anyLong());
    }

    @Test
    @DisplayName("queryWithMute: cache miss + lock acquired → queries DB and caches")
    void queryWithMute_CacheMiss_LockAcquired_QueriesDb() {
        when(valueOperations.get("cache:key:1")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:shop:1"), anyString(), anyLong(), any()))
                .thenReturn(true);

        Map<String, Object> dbResult = testData("key", "db-result");
        Function<Long, Map> dbFallback = id -> dbResult;
        Map result = cacheClient.queryWithMute("cache:key:", 1L, Map.class,
                dbFallback, 10L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals("db-result", result.get("key"));
        verify(valueOperations).set(eq("cache:key:1"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), eq(java.util.Collections.singletonList("lock:shop:1")), anyString());
    }
}
