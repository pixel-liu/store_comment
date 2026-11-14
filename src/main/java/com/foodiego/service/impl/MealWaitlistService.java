package com.foodiego.service.impl;

import com.foodiego.dto.Result;
import com.foodiego.entity.MealBatch;
import com.foodiego.mapper.MealMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.foodiego.utils.RedisConstants.SECKILL_INFO_KEY;
import static com.foodiego.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.foodiego.utils.RedisConstants.waitlistRecordKey;

@Service
@RequiredArgsConstructor
@Slf4j
public class MealWaitlistService {
    private static final String ACTIVE = "meal:waitlist:active";
    private static final String USER_PREFIX = "meal:waitlist:user:";
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("meal-waitlist.lua"));
        SCRIPT.setResultType(String.class);
    }
    private final StringRedisTemplate redis;
    private final MealMapper meals;

    @Value("${foodiego.meals.waitlist-hold-ms:300000}")
    private long holdMillis;

    public record Entry(String batchId, String title, String shopName, String attempt, String status,
                        Long position, Instant expiresAt, String reservationId) {}

    static List<String> inventoryKeys(long batch) {
        return List.of("meal:waitlist:queue:" + batch, "meal:waitlist:holds:" + batch, waitlistRecordKey(batch));
    }

    public Result join(long batch, long user) {
        if (batch <= 0) return Result.fail("批次编号无效");
        String result = execute("join", batch, user, "");
        return switch (result) {
            case "CLOSED" -> Result.fail("当前批次未开放候补或已截止");
            case "ALREADY_BOOKED" -> Result.fail("本批次已预约过，每个账号限一份");
            case "AVAILABLE" -> Result.fail("已有余量，请直接预约");
            case "RELEASING" -> Result.fail("上一轮候补正在释放，请稍后重试");
            default -> {
                MealBatch meal = meals.findBatch(batch);
                yield meal == null ? Result.fail("找不到此批次") : Result.ok(entry(meal, result));
            }
        };
    }

    public Result leave(long batch, long user, String attempt) {
        if (!validAttempt(attempt)) return Result.fail("候补编号无效");
        String result = execute("leave", batch, user, attempt);
        return "STALE".equals(result) ? Result.fail("候补状态已变化，请刷新") : Result.ok();
    }

    public List<Entry> list(long user) {
        String key = USER_PREFIX + user;
        long now = System.currentTimeMillis();
        redis.opsForZSet().removeRangeByScore(key, 0, now);
        Set<String> ids = redis.opsForZSet().reverseRange(key, 0, 49);
        List<Entry> entries = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return entries;
        for (MealBatch batch : meals.listWaitlistBatches(ids.stream().map(Long::parseLong).toList())) {
            String record = execute("status", batch.getVoucherId(), user, "");
            if (!"MISSING".equals(record)) entries.add(entry(batch, record));
        }
        return entries;
    }

    @Scheduled(fixedDelayString = "${foodiego.meals.waitlist-poll-ms:1000}")
    public void offerPortions() {
        Set<String> due = redis.opsForZSet().rangeByScore(ACTIVE, 0, System.currentTimeMillis(), 0, 100);
        if (due == null) return;
        for (String batch : due) {
            try {
                execute("dispatch", Long.parseLong(batch), 0, "");
            } catch (RuntimeException error) {
                log.warn("候补批次 {} 分配失败，将重试", batch, error);
            }
        }
    }

    static boolean validAttempt(String attempt) {
        return attempt != null && attempt.matches("[1-9]\\d{0,17}");
    }

    private String execute(String action, long batch, long user, String attempt) {
        List<String> keys = new ArrayList<>(List.of(SECKILL_STOCK_KEY + batch, SECKILL_INFO_KEY + batch, "seckill:order:" + batch));
        keys.addAll(inventoryKeys(batch));
        keys.addAll(List.of("meal:waitlist:sequence:" + batch, ACTIVE, USER_PREFIX + user));
        String result = redis.execute(SCRIPT, keys, action, Long.toString(batch), Long.toString(user), attempt,
                Long.toString(Math.max(1000, holdMillis)));
        if (result == null) throw new IllegalStateException("候补操作未完成");
        return result;
    }

    private Entry entry(MealBatch batch, String value) {
        String[] fields = value.split(":");
        String status = fields[0];
        return new Entry(batch.getVoucherId().toString(), batch.getTitle(), batch.getShopName(), fields[1], status,
                "WAITING".equals(status) ? Long.valueOf(fields[2]) : null,
                "OFFERED".equals(status) ? Instant.ofEpochMilli(Long.parseLong(fields[2])) : null,
                ("CONFIRMED".equals(status) || "FAILED".equals(status)) ? fields[2] : null);
    }
}
