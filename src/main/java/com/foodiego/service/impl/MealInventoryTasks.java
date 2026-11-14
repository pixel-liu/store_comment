package com.foodiego.service.impl;

import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.MealBatch;
import com.foodiego.mapper.MealMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.foodiego.utils.RedisConstants.MEAL_RESTOCKED_KEY;
import static com.foodiego.utils.RedisConstants.SECKILL_INFO_KEY;
import static com.foodiego.utils.RedisConstants.SECKILL_STOCK_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class MealInventoryTasks {
    private static final DefaultRedisScript<Long> INIT_BATCH = loadScript("init-meal-batch.lua");
    private static final DefaultRedisScript<Long> RESTOCK_BATCH = loadScript("restock-meal-batch.lua");
    private static final DefaultRedisScript<Long> CLOSE_BOOKING = loadScript("close-meal-booking.lua");

    private final MealMapper mealMapper;
    private final StringRedisTemplate redis;

    @Value("${foodiego.seckill.zone-id:Asia/Shanghai}")
    private String zoneId;

    @Scheduled(fixedDelayString = "${foodiego.seckill.outbox-poll-ms:1000}")
    public void applyPendingClosures() {
        for (MealBatch batch : mealMapper.listPendingClosures()) {
            try {
                applyBookingClose(batch);
            } catch (RuntimeException e) {
                log.warn("余量餐批次 {} 关单失败，将重试", batch.getVoucherId(), e);
            }
        }
    }

    void applyBookingClose(MealBatch batch) {
        Long closed = redis.execute(CLOSE_BOOKING, List.of(SECKILL_INFO_KEY + batch.getVoucherId()),
                millis(batch.getEndTime(), ZoneId.of(zoneId)));
        if (!Long.valueOf(1L).equals(closed)) {
            throw new IllegalStateException("未能停止接单");
        }
        mealMapper.markBookingClosed(batch.getVoucherId());
    }

    @Scheduled(fixedDelayString = "${foodiego.meals.expiry-poll-ms:15000}")
    public void expireOverduePickups() {
        List<Long> ids = mealMapper.listOverduePickups(LocalDateTime.now(ZoneId.of(zoneId)));
        if (!ids.isEmpty()) {
            mealMapper.expirePickups(ids);
        }
    }

    @Scheduled(fixedDelayString = "${foodiego.seckill.outbox-poll-ms:1000}")
    public void publishPendingBatches() {
        ZoneId zone = ZoneId.of(zoneId);
        for (MealBatch batch : mealMapper.listUnpublishedBatches(LocalDateTime.now(zone))) {
            long batchId = batch.getVoucherId();
            try {
                Long initialized = redis.execute(INIT_BATCH,
                        List.of(SECKILL_STOCK_KEY + batchId, SECKILL_INFO_KEY + batchId),
                        batch.getStock().toString(), millis(batch.getBeginTime(), zone),
                        millis(batch.getEndTime(), zone), millis(batch.getEndTime().plusDays(1), zone),
                        millis(batch.getPickupEndTime().plusDays(1), zone));
                if (Long.valueOf(1L).equals(initialized)) {
                    mealMapper.markPublished(batchId);
                }
            } catch (RuntimeException e) {
                log.warn("余量餐批次 {} 发布失败，将重试", batchId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${foodiego.seckill.outbox-poll-ms:1000}")
    public void replenishCancelledReservations() {
        ZoneId zone = ZoneId.of(zoneId);
        for (VoucherOrderDTO task : mealMapper.listPendingRestocks()) {
            long orderId = task.getId();
            long batchId = task.getVoucherId();
            try {
                MealBatch batch = mealMapper.findBatch(batchId);
                Long updated = redis.execute(RESTOCK_BATCH,
                        List.of(SECKILL_STOCK_KEY + batchId, SECKILL_INFO_KEY + batchId,
                                MEAL_RESTOCKED_KEY + orderId), millis(batch.getEndTime(), zone));
                if (updated != null && updated >= 0) {
                    mealMapper.markRestocked(orderId);
                }
            } catch (RuntimeException e) {
                log.warn("余量餐预约 {} 回补失败，将重试", orderId, e);
            }
        }
    }

    private String millis(LocalDateTime time, ZoneId zone) {
        return Long.toString(time.atZone(zone).toInstant().toEpochMilli());
    }

    private static DefaultRedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
