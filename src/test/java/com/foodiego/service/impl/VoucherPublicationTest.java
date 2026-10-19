package com.foodiego.service.impl;

import com.foodiego.entity.Voucher;
import com.foodiego.service.ISeckillVoucherService;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VoucherPublicationTest {
    VoucherServiceImpl service;
    StringRedisTemplate redis;
    ISeckillVoucherService seckill;

    @BeforeEach void setup() {
        service = spy(new VoucherServiceImpl());
        redis = mock(StringRedisTemplate.class);
        seckill = mock(ISeckillVoucherService.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplates", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckill);
        doReturn(true).when(service).save(any(Voucher.class));
        when(seckill.save(any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }
    Voucher voucher() {
        Voucher value = new Voucher(); value.setId(100L); value.setStock(10);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        value.setBeginTime(now.plusHours(1)); value.setEndTime(now.plusHours(2));
        return value;
    }
    @Test void publishesEpochTimesOnlyAfterDatabaseCommit() {
        Voucher voucher = voucher();
        service.addSeckillVoucher(voucher);
        verifyNoInteractions(redis);
        long begin = voucher.getBeginTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long end = voucher.getEndTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.get(0).afterCommit();
        verify(redis).execute(any(DefaultRedisScript.class), anyList(),
                eq("10"), eq(Long.toString(begin)), eq(Long.toString(end)), eq(Long.toString(end+86400000)));
    }
    @Test void rollbackDoesNotPublishActivity() {
        service.addSeckillVoucher(voucher());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(redis);
    }
    @Test void rejectsNonPositiveStockBeforeWritingDatabase() {
        Voucher voucher = voucher(); voucher.setStock(0);
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verify(service,never()).save(any(Voucher.class)); verifyNoInteractions(redis);
    }
    @Test void rejectsReversedOrExpiredWindowBeforeWritingDatabase() {
        Voucher reversed = voucher(); reversed.setEndTime(reversed.getBeginTime());
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(reversed));
        Voucher expired = voucher(); expired.setBeginTime(expired.getBeginTime().minusDays(2));
        expired.setEndTime(expired.getEndTime().minusDays(2));
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(expired));
        verify(service,never()).save(any(Voucher.class)); verifyNoInteractions(redis);
    }
}
