package com.foodiego;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import com.foodiego.service.impl.MealInventoryTasks;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static com.foodiego.utils.RedisConstants.LOGIN_USER_KEY;
import static com.foodiego.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MealReservationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MealInventoryTasks inventoryTasks;

    @Test
    void lastPortionHasOneWinnerAndCancelAndRedeemAreMutuallyExclusive() throws Exception {
        long staffId = 10001;
        String staff = session(staffId);
        List<String> students = IntStream.range(10002, 10022).mapToObj(this::session).toList();
        jdbc.update("INSERT INTO tb_shop_staff(shop_id,user_id) VALUES (1,?)", staffId);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String batch = success(post("/staff/shops/1/meal-batches").header("Authorization", staff)
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "title", "集成测试余量餐", "stock", 1, "beginTime", now.minusMinutes(2),
                        "endTime", now.plusMinutes(5), "pickupBeginTime", now.minusMinutes(1),
                        "pickupEndTime", now.plusMinutes(10)))))
                .get("id").asText();
        String stockKey = SECKILL_STOCK_KEY + batch;
        await().atMost(Duration.ofSeconds(15)).until(() -> "1".equals(redis.opsForValue().get(stockKey)));
        String progressPath = "/staff/shops/1/meal-batches";
        mvc.perform(get(progressPath)).andExpect(status().isUnauthorized());
        mvc.perform(get("/blog/hot")).andExpect(status().isNotFound());
        assertFalse(perform(get(progressPath).header("Authorization", students.get(0))).get("success").asBoolean());
        assertFalse(perform(get("/staff/shops/2/meal-batches").header("Authorization", staff)).get("success").asBoolean());
        assertProgress(progressPath, staff, batch, 1, 0, 0);

        List<CompletableFuture<JsonNode>> attempts = students.stream().map(token -> CompletableFuture.supplyAsync(() ->
                perform(post("/meal-batches/{batch}/reservations", batch).header("Authorization", token)))).toList();
        List<Integer> winners = IntStream.range(0, attempts.size())
                .filter(index -> attempts.get(index).join().get("success").asBoolean()).boxed().toList();
        assertEquals(1, winners.size());
        int winner = winners.get(0);
        String student = students.get(winner);
        String anotherStudent = students.get((winner + 1) % students.size());
        JsonNode reservation = attempts.get(winner).join().get("data");
        assertTrue(reservation.get("id").isTextual());
        String path = "/meal-reservations/" + reservation.get("id").asText();
        assertFalse(perform(get(path).header("Authorization", anotherStudent)).get("success").asBoolean());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals("AWAITING_PICKUP", success(get(path).header("Authorization", student)).get("status").asText()));
        String pickupCode = success(get(path).header("Authorization", student)).get("pickupCode").asText();
        assertEquals("0", redis.opsForValue().get(stockKey));
        assertEquals(0, databaseStock(batch));
        assertProgress(progressPath, staff, batch, 0, 1, 0);

        var cancel = CompletableFuture.supplyAsync(() -> perform(post(path + "/cancel").header("Authorization", student)));
        var redeem = CompletableFuture.supplyAsync(() -> perform(post("/staff/shops/1" + path + "/redeem")
                .header("Authorization", staff).contentType("application/json")
                .content("{\"pickupCode\":\"" + pickupCode + "\"}")));
        boolean cancelled = cancel.join().get("success").asBoolean();
        assertNotEquals(cancelled, redeem.join().get("success").asBoolean());
        assertEquals(cancelled ? "CANCELLED" : "REDEEMED", success(get(path).header("Authorization", student)).get("status").asText());
        if (cancelled) {
            assertFalse(perform(post(path + "/cancel").header("Authorization", student)).get("success").asBoolean());
            await().atMost(Duration.ofSeconds(15)).until(() -> "1".equals(redis.opsForValue().get(stockKey)));
            assertEquals(1, databaseStock(batch));
            assertProgress(progressPath, staff, batch, 1, 0, 0);
            assertTrue(perform(post("/meal-batches/{batch}/reservations", batch)
                    .header("Authorization", anotherStudent)).get("success").asBoolean());
        } else {
            assertProgress(progressPath, staff, batch, 0, 0, 1);
        }
    }

    @Test
    void earlyCloseRejectsNewRequestsAndKeepsAnAcceptedOrderWaitingForMysql() throws Exception {
        String staff = session(10100);
        String student = session(10101);
        jdbc.update("INSERT INTO tb_shop_staff(shop_id,user_id) VALUES (1,?)", 10100);
        String batch = publishOperationsBatch(staff);
        String batchPath = "/staff/shops/1/meal-batches/" + batch;
        String bookingPath = "/meal-batches/" + batch + "/reservations";
        String reservationPath;
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var query = lock.prepareStatement("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ? FOR UPDATE")) {
                query.setLong(1, Long.parseLong(batch));
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); }
            }
            String id = success(post(bookingPath).header("Authorization", student)).get("id").asText();
            reservationPath = "/meal-reservations/" + id;
            assertEquals("PROCESSING", success(get(reservationPath).header("Authorization", student)).get("status").asText());
            assertEquals("BOOKING_CLOSED", success(post(batchPath + "/close-booking").header("Authorization", staff))
                    .get("status").asText());
            assertFalse(perform(post(bookingPath).header("Authorization", session(10102))).get("success").asBoolean());
            lock.commit();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals("AWAITING_PICKUP",
                success(get(reservationPath).header("Authorization", student)).get("status").asText()));
        JsonNode detail = success(get(batchPath).header("Authorization", staff));
        assertEquals(2, detail.get("portions").asInt());
        assertEquals(1, detail.get("remaining").asInt());
        assertEquals(1, detail.get("awaitingPickup").asInt());
    }

    @Test
    void overdueSettlementAndRedemptionLeaveExactlyOneFinalOutcome() throws Exception {
        String staff = session(10200);
        String student = session(10201);
        jdbc.update("INSERT INTO tb_shop_staff(shop_id,user_id) VALUES (1,?)", 10200);
        String batch = publishOperationsBatch(staff);
        String id = success(post("/meal-batches/" + batch + "/reservations").header("Authorization", student)).get("id").asText();
        String path = "/meal-reservations/" + id;
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals("AWAITING_PICKUP",
                success(get(path).header("Authorization", student)).get("status").asText()));
        String code = success(get(path).header("Authorization", student)).get("pickupCode").asText();
        LocalDateTime boundary = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(3).withNano(0);
        jdbc.update("UPDATE tb_meal_batch SET pickup_end_time = ? WHERE voucher_id = ?", boundary, batch);
        var redeem = CompletableFuture.supplyAsync(() -> {
            await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(5))
                    .until(() -> !LocalDateTime.now(ZoneId.of("Asia/Shanghai")).isBefore(boundary.minusNanos(100_000_000)));
            return perform(post("/staff/shops/1" + path + "/redeem").header("Authorization", staff)
                    .contentType("application/json").content("{\"pickupCode\":\"" + code + "\"}")).get("success").asBoolean();
        });
        var settle = CompletableFuture.runAsync(() -> {
            await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(5))
                    .until(() -> !LocalDateTime.now(ZoneId.of("Asia/Shanghai")).isBefore(boundary));
            inventoryTasks.expireOverduePickups();
        });
        boolean redeemed = redeem.join();
        settle.join();
        int expected = redeemed ? 3 : 8;
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(expected,
                jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, id)));
        JsonNode filtered = success(get("/meal-reservations").param("status", redeemed ? "REDEEMED" : "EXPIRED")
                .header("Authorization", student));
        assertEquals(id, filtered.get("items").get(0).get("id").asText());
        JsonNode detail = success(get("/staff/shops/1/meal-batches/" + batch).header("Authorization", staff));
        assertEquals(redeemed ? 1 : 0, detail.get("redeemed").asInt());
        assertEquals(redeemed ? 0 : 1, detail.get("expired").asInt());
        assertEquals(1, databaseStock(batch));
    }

    private String publishOperationsBatch(String staff) throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String batch = success(post("/staff/shops/1/meal-batches").header("Authorization", staff)
                .contentType("application/json").content(json.writeValueAsString(Map.of(
                        "title", "运营集成测试", "stock", 2, "beginTime", now.minusMinutes(2),
                        "endTime", now.plusMinutes(5), "pickupBeginTime", now.minusMinutes(1),
                        "pickupEndTime", now.plusMinutes(10))))).get("id").asText();
        await().atMost(Duration.ofSeconds(15)).until(() -> "2".equals(redis.opsForValue().get(SECKILL_STOCK_KEY + batch)));
        return batch;
    }

    private void assertProgress(String path, String token, String batch, int remaining, int waiting, int redeemed) {
        for (JsonNode item : success(get(path).header("Authorization", token))) {
            if (batch.equals(item.get("id").asText())) {
                assertEquals(remaining, item.get("remaining").asInt());
                assertEquals(waiting, item.get("awaitingPickup").asInt());
                assertEquals(redeemed, item.get("redeemed").asInt());
                return;
            }
        }
        fail("Missing batch progress for " + batch);
    }

    private int databaseStock(String batch) {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, batch);
    }

    private String session(long id) {
        String token = UUID.randomUUID().toString();
        String key = LOGIN_USER_KEY + token;
        redis.opsForHash().putAll(key, Map.of("id", Long.toString(id), "nickName", "test"));
        redis.expire(key, Duration.ofMinutes(5));
        return token;
    }

    private JsonNode success(RequestBuilder request) {
        JsonNode response = perform(request);
        assertTrue(response.get("success").asBoolean(), response.toString());
        return response.get("data");
    }

    private JsonNode perform(RequestBuilder request) {
        try {
            return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
