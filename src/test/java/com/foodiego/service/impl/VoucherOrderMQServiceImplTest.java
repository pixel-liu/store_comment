package com.foodiego.service.impl;

import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.entity.VoucherOrder;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherService;
import com.foodiego.entity.Voucher;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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

    @MockBean
    private IVoucherService voucherService;

    @MockBean
    private MealMapper mealMapper;

    @BeforeEach
    void setUp() {
        // Set up a mock user in ThreadLocal
        UserDTO testUser = new UserDTO();
        testUser.setId(1L);
        testUser.setNickName("testUser");
        UserHolder.saveUser(testUser);

    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // ────────────────────── secKillVoucher() tests ──────────────────────

    @Test
    @DisplayName("Normal voucher order creates an order through the active service")
    void voucher_ActiveNormalVoucher_CreatesOrder() {
        Voucher voucher = new Voucher();
        voucher.setId(8L);
        voucher.setType(0);
        voucher.setStatus(1);
        when(voucherService.getById(8L)).thenReturn(voucher);
        when(redisIdWorker.nextId("order")).thenReturn(7001L);
        when(voucherOrderMapper.insert(org.mockito.ArgumentMatchers.<VoucherOrder>any())).thenReturn(1);

        Result result = voucherOrderService.voucher(8L);

        assertTrue(result.getSuccess());
        assertEquals(7001L, result.getData());
        verify(voucherOrderMapper).insert(org.mockito.ArgumentMatchers.<VoucherOrder>argThat(order -> order.getVoucherId().equals(8L)
                && order.getUserId().equals(1L)));
    }

    @Test
    @DisplayName("Lua returns 0 (success) → generates order ID and appends a durable outbox event")
    void secKillVoucher_LuaSuccess_GeneratesOrderAndAppendsOutboxEvent() {
        // Arrange
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        when(redisIdWorker.nextId("order")).thenReturn(99999L);

        // Act
        Result result = voucherOrderService.secKillVoucher(100L);

        // Assert
        assertTrue(result.getSuccess(), "Seckill should succeed");
        assertEquals(99999L, result.getData());

        // The Lua call receives the ID and records the outbox entry atomically with the reservation.
        verify(redisIdWorker).nextId("order");
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("Lua returns 1 → 'No stock available'")
    void secKillVoucher_NoStock_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(redisIdWorker).nextId("order");
    }

    @Test
    @DisplayName("Lua returns 2 → 'Duplicate order not allowed'")
    void secKillVoucher_Duplicate_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(2L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
    }

    @Test
    @DisplayName("Lua returns unexpected value → generic failure")
    void secKillVoucher_UnexpectedLuaResult_ReturnsFail() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(99L);

        Result result = voucherOrderService.secKillVoucher(100L);

        assertFalse(result.getSuccess());
    }

    // ────────────────────── retryMqFromRedis() tests ──────────────────────

    @Test
    @DisplayName("Retry: empty Redis backup list → no MQ sends")
    void retryMqFromRedis_EmptyList_NoOp() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);
        when(listOps.rightPopAndLeftPush(anyString(), anyString())).thenReturn(null);

        voucherOrderService.retryMqFromRedis();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Retry: malformed outbox entry remains in processing for inspection and never sends MQ")
    void retryMqFromRedis_MalformedEntry_IsNotDiscarded() {
        ListOperations<String, String> listOps = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);
        when(listOps.rightPopAndLeftPush("mq:outbox:order:", "mq:outbox:processing:"))
                .thenReturn("not-an-outbox-entry")
                .thenReturn(null);

        voucherOrderService.retryMqFromRedis();

        verify(listOps, times(2)).rightPopAndLeftPush("mq:outbox:order:", "mq:outbox:processing:");
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    // ────────────────────── Helper methods ──────────────────────

}
