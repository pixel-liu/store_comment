package com.foodiego;

import com.foodiego.dto.LoginFormDTO;
import com.foodiego.dto.Result;
import com.foodiego.entity.Voucher;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherService;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.service.IUserService;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.foodiego.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;

/**
 * Integration test for the seckill (flash sale) flow.
 * Uses Testcontainers for real MySQL + Redis + RabbitMQ.
 */
@DisplayName("Seckill Integration Tests")
class SeckillIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private IVoucherService voucherService;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    private Long testVoucherId;
    private Long testUserId = 1L;

    @AfterEach
    void cleanupUser() { UserHolder.removeUser(); }

    @BeforeEach
    void setUp() {
        // Login user 1
        LoginFormDTO form = LoginFormDTO.builder()
                .phone("13686869696").code("123456").build();
        // Pre-set code in Redis
        stringRedisTemplate.opsForValue().set("login:code:13686869696", "123456");
        Result loginResult = userService.login(form, null);
        // Set user in ThreadLocal for service calls
        com.foodiego.dto.UserDTO userDTO = new com.foodiego.dto.UserDTO();
        userDTO.setId(1L);
        userDTO.setNickName("testUser");
        UserHolder.saveUser(userDTO);

        // Create a test seckill voucher with 50 stock
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("Flash Sale Test Voucher");
        voucher.setSubTitle("50% Off");
        voucher.setRules("Test rules");
        voucher.setPayValue(5000L);
        voucher.setActualValue(10000L);
        voucher.setType(1); // seckill type
        voucher.setStatus(1);
        voucher.setStock(50);
        voucher.setBeginTime(LocalDateTime.now().minusMinutes(10));
        voucher.setEndTime(LocalDateTime.now().plusHours(1));

        voucherService.addSeckillVoucher(voucher);

        // Find the created voucher
        testVoucherId = voucher.getId();

        // Set Redis stock
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + testVoucherId, "50");
    }

    @Test
    @DisplayName("Single seckill order succeeds with correct stock deduction")
    void seckill_SingleOrder_Success() {
        Result result = voucherOrderService.secKillVoucher(testVoucherId);

        assertTrue(result.getSuccess(), "Seckill should succeed: " + result.getErrorMsg());
        assertNotNull(result.getData());

        // Redis stock should have been decremented by 1
        String stockStr = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + testVoucherId);
        assertEquals("49", stockStr, "Redis stock should be 49 after one order");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertNotNull(voucherOrderService.getById((Long) result.getData()));
            assertEquals(49, seckillVoucherService.getById(testVoucherId).getStock());
        });
    }

    @Test
    @DisplayName("Duplicate order → returns '不能重复下单'")
    void seckill_DuplicateOrder_ReturnsFail() {
        // First order
        Result first = voucherOrderService.secKillVoucher(testVoucherId);
        assertTrue(first.getSuccess());

        // Second order from same user
        Result second = voucherOrderService.secKillVoucher(testVoucherId);

        assertFalse(second.getSuccess());
        assertEquals("不能重复下单", second.getErrorMsg());
    }

    @Test
    @DisplayName("Concurrent orders: 50 stock, 100 requests → no overselling, exactly 50 succeed")
    void seckill_Concurrent_NoOverselling() throws InterruptedException {
        // Reset stock
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + testVoucherId, "50");

        int totalRequests = 100;
        int stock = 50;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Each thread represents a different user (userId 100-199)
        for (int i = 0; i < totalRequests; i++) {
            final long userId = 100 + i;
            executor.submit(() -> {
                try {
                    com.foodiego.dto.UserDTO u = new com.foodiego.dto.UserDTO();
                    u.setId(userId);
                    u.setNickName("user-" + userId);
                    UserHolder.saveUser(u);

                    Result result = voucherOrderService.secKillVoucher(testVoucherId);
                    if (result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                    UserHolder.removeUser();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Concurrent requests must finish");
        executor.shutdown();

        assertEquals(stock, successCount.get(), "Exactly 50 orders should succeed");
        assertEquals(totalRequests - stock, failCount.get(), "50 orders should fail");

        // Final stock should be 0
        String stockStr = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + testVoucherId);
        assertEquals("0", stockStr, "Final stock should be 0 — no overselling");
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(stock, voucherOrderService.query().eq("voucher_id", testVoucherId).count());
            assertEquals(0, seckillVoucherService.getById(testVoucherId).getStock());
        });
    }
}
