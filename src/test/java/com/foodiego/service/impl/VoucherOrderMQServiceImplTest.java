package com.foodiego.service.impl;

import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.VoucherOrder;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoucherOrderMQServiceImpl} — seckill business logic.
 * <p>
 * Uses {@code @SpringBootTest} + {@code @MockBean} because the service extends
 * MyBatis-Plus {@code ServiceImpl}, which cannot be cleanly tested with pure Mockito.
 */
@SpringBootTest(classes = {
        VoucherOrderMQServiceImpl.class,
        VoucherOrderMapper.class
})
@DisplayName("VoucherOrderMQServiceImpl Unit Tests")
class VoucherOrderMQServiceImplTest {

    @Autowired
    private VoucherOrderMQServiceImpl voucherOrderService;

    @MockBean
    private ISeckillVoucherService seckillVoucherService;

    @MockBean
    private RedisIdWorker redisIdWorker;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private VoucherOrderMapper voucherOrderMapper;

    @MockBean
    private com.foodiego.mapper.SeckillVoucherMapper seckillVoucherMapper;

    @MockBean
    private com.foodiego.mapper.VoucherOrderFailureMapper voucherOrderFailureMapper;

    @BeforeEach
    void setUp() {
        // Set up a mock user in ThreadLocal
        UserDTO testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("testUser");
        UserHolder.saveUser(testUser);

        // Replace async MQ executor with synchronous one for deterministic testing
        voucherOrderService.asyncMqExecutor.shutdownNow();
        setField("asyncMqExecutor", new ThreadPoolExecutor(
                1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy()
        ));
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        voucherOrderService.asyncMqExecutor.shutdownNow();
    }

    // ────────────────────── secKillVoucher() tests ──────────────────────

    @Test
    @DisplayName("Lua returns 0 (success) → generates order ID and sends MQ")
    void secKillVoucher_LuaSuccess_GeneratesOrderAndSendsMq() {
        // Arrange
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);
        when(redisIdWorker.nextId("order")).thenReturn(99999L);

        // Act
        Result result = voucherOrderService.secKillVoucher(100L);

        // Assert
        assertTrue(result.getSuccess(), "Seckill should succeed");
        assertEquals(99999L, result.getData());

        // Verify MQ sent (async, so wait briefly)
        verify(redisIdWorker).nextId("order");
        sleep(200);
        verify(rabbitTemplate).convertAndSend(
                eq("seckill.order.exchange"),
                eq("seckill.order"),
                any(Object.class));
    }

    @Test
    @DisplayName("Lua returns 1 → 'No stock available'")
    void secKillVoucher_NoStock_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(redisIdWorker, never()).nextId(anyString());
    }

    @Test
    @DisplayName("Lua returns 2 → 'Duplicate order not allowed'")
    void secKillVoucher_Duplicate_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(2L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
    }

    @Test
    @DisplayName("Lua returns unexpected value → generic failure")
    void secKillVoucher_UnexpectedLuaResult_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(99L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
    }

    @Test
    @DisplayName("Lua success but MQ send fails → pushes to Redis backup list")
    void secKillVoucher_MqSendFails_PushesToRedisBackup() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);
        when(redisIdWorker.nextId("order")).thenReturn(88888L);

        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        doThrow(new AmqpException("Connection refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        Result result = voucherOrderService.secKillVoucher(100L);

        // Should still return success to the user (optimistic: MQ sent async)
        assertTrue(result.getSuccess());
        assertEquals(88888L, result.getData());

        // Wait for async send to complete
        sleep(500);

        // Verify backup was pushed to Redis
        verify(listOps).leftPush(eq("mq:retry:order:"), contains("88888:100:1"));
    }

    // ────────────────────── retryMqFromRedis() tests ──────────────────────

    @Test
    @DisplayName("Retry: empty Redis backup list → no MQ sends")
    void retryMqFromRedis_EmptyList_NoOp() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPop("mq:retry:order:")).thenReturn(null);

        voucherOrderService.retryMqFromRedis();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Retry: successful resend → entry removed from list")
    void retryMqFromRedis_Success_PopsEntry() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        // Return one entry then empty
        when(listOps.rightPop("mq:retry:order:"))
                .thenReturn("12345:100:1:0")
                .thenReturn(null);

        voucherOrderService.retryMqFromRedis();

        // Verify MQ was sent
        verify(rabbitTemplate).convertAndSend(
                eq("seckill.order.exchange"),
                eq("seckill.order"),
                any(Object.class));
    }

    @Test
    @DisplayName("Retry: failed with retryCount<3 → re-pushed to list")
    void retryMqFromRedis_RetryLessThan3_RePushes() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPop("mq:retry:order:"))
                .thenReturn("12345:100:1:0")  // retryCount=0
                .thenReturn(null);
        doThrow(new AmqpException("fail")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        voucherOrderService.retryMqFromRedis();

        // Should re-push with incremented retryCount
        verify(listOps).leftPush(eq("mq:retry:order:"), eq("12345:100:1:1"));
    }

    @Test
    @DisplayName("Retry: failed with retryCount>=3 → drops message (no re-push)")
    void retryMqFromRedis_RetryExhausted_Drops() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPop("mq:retry:order:"))
                .thenReturn("12345:100:1:3")  // retryCount=3
                .thenReturn(null);
        doThrow(new AmqpException("fail")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        voucherOrderService.retryMqFromRedis();

        // Should NOT re-push
        verify(listOps, never()).leftPush(anyString(), anyString());
    }

    // ────────────────────── Helper methods ──────────────────────

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setField(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = VoucherOrderMQServiceImpl.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(voucherOrderService, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
