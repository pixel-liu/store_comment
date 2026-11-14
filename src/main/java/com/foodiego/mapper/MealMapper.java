package com.foodiego.mapper;

import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.MealBatch;
import com.foodiego.entity.MealPickup;
import com.foodiego.entity.Shop;
import com.foodiego.entity.User;
import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

public interface MealMapper {
    String BATCH_QUERY = """
            SELECT b.voucher_id, v.shop_id, s.name AS shop_name, s.address AS shop_address, v.title,
                   a.stock, a.begin_time, a.end_time, b.pickup_begin_time, b.pickup_end_time
            FROM tb_meal_batch b
            JOIN tb_voucher v ON v.id = b.voucher_id
            JOIN tb_seckill_voucher a ON a.voucher_id = b.voucher_id
            JOIN tb_shop s ON s.id = v.shop_id
            """;
    String PICKUP_QUERY = """
            SELECT o.id AS order_id, o.voucher_id, o.user_id, o.status, p.pickup_code,
                   v.shop_id, s.name AS shop_name, s.address AS shop_address, v.title, a.end_time AS booking_end_time,
                   b.pickup_begin_time, b.pickup_end_time
            FROM tb_meal_pickup p
            JOIN tb_voucher_order o ON o.id = p.order_id
            JOIN tb_meal_batch b ON b.voucher_id = o.voucher_id
            JOIN tb_voucher v ON v.id = o.voucher_id
            JOIN tb_seckill_voucher a ON a.voucher_id = o.voucher_id
            JOIN tb_shop s ON s.id = v.shop_id
            """;

    @Select(BATCH_QUERY + "WHERE b.voucher_id = #{voucherId}")
    MealBatch findBatch(long voucherId);

    @Select("""
            <script>
            """ + BATCH_QUERY + """
            WHERE b.voucher_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY a.end_time DESC, b.voucher_id DESC
            </script>
            """)
    List<MealBatch> listWaitlistBatches(@Param("ids") List<Long> ids);

    @Select("""
            <script>
            """ + BATCH_QUERY + """
            WHERE b.published = 1 AND b.booking_closed_at IS NULL AND v.status = 1 AND a.end_time > #{now}
            <if test="windowId != null">AND v.shop_id = #{windowId}</if>
            <if test="titlePattern != null">AND v.title LIKE #{titlePattern} ESCAPE '!'</if>
            <if test="today != null">
                AND b.pickup_begin_time &lt; #{tomorrow} AND b.pickup_end_time &gt; #{today}
            </if>
            <choose>
                <when test="status == 'SCHEDULED'">AND a.begin_time &gt; #{now}</when>
                <when test="status != null">AND a.begin_time &lt;= #{now}</when>
            </choose>
            ORDER BY a.begin_time, b.voucher_id LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MealBatch> listAvailableBatches(@Param("now") LocalDateTime now, @Param("windowId") Long windowId,
                                         @Param("titlePattern") String titlePattern, @Param("status") String status,
                                         @Param("today") LocalDateTime today, @Param("tomorrow") LocalDateTime tomorrow,
                                         @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            SELECT s.id, s.name, s.address FROM tb_shop s
            WHERE s.id = #{windowId}
              AND (EXISTS (SELECT 1 FROM tb_shop_staff st WHERE st.shop_id = s.id)
                   OR EXISTS (SELECT 1 FROM tb_voucher v JOIN tb_meal_batch b ON b.voucher_id = v.id
                              WHERE v.shop_id = s.id AND b.published = 1))
            """)
    Shop findCampusWindow(long windowId);

    @Select("""
            SELECT DISTINCT s.id, s.name
            FROM tb_meal_batch b
            JOIN tb_voucher v ON v.id = b.voucher_id
            JOIN tb_seckill_voucher a ON a.voucher_id = b.voucher_id
            JOIN tb_shop s ON s.id = v.shop_id
            WHERE b.published = 1 AND b.booking_closed_at IS NULL AND v.status = 1 AND a.end_time > #{now}
              AND b.pickup_begin_time < #{tomorrow} AND b.pickup_end_time > #{today}
            ORDER BY s.name, s.id
            """)
    List<Shop> listTodayWindows(@Param("now") LocalDateTime now, @Param("today") LocalDateTime today,
                                @Param("tomorrow") LocalDateTime tomorrow);

    String STAFF_BATCHES = """
            SELECT b.voucher_id, v.title, a.stock, a.begin_time, a.end_time,
                   b.pickup_begin_time, b.pickup_end_time, b.published, b.initial_stock, b.booking_closed_at, b.close_applied,
                   COUNT(CASE WHEN o.status = 7 AND b.pickup_end_time > #{now} THEN 1 END) AS awaiting_pickup,
                   COUNT(CASE WHEN o.status = 3 THEN 1 END) AS redeemed,
                   COUNT(CASE WHEN o.status = 4 THEN 1 END) AS cancelled,
                   COUNT(CASE WHEN o.status = 8 OR (o.status = 7 AND b.pickup_end_time <= #{now}) THEN 1 END) AS expired
            FROM tb_meal_batch b
            JOIN tb_voucher v ON v.id = b.voucher_id
            JOIN tb_seckill_voucher a ON a.voucher_id = b.voucher_id
            LEFT JOIN (tb_voucher_order o JOIN tb_meal_pickup p ON p.order_id = o.id)
                ON o.voucher_id = b.voucher_id AND o.status IN (3, 4, 7, 8)
            WHERE v.shop_id = #{shopId}
            """;
    String STAFF_BATCH_GROUP = """
            GROUP BY b.voucher_id, v.title, a.stock, a.begin_time, a.end_time,
                     b.pickup_begin_time, b.pickup_end_time, b.published, b.initial_stock, b.booking_closed_at, b.close_applied
            """;

    @Select(STAFF_BATCHES + STAFF_BATCH_GROUP + "ORDER BY b.voucher_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<MealBatch> listStaffBatches(@Param("shopId") long shopId, @Param("now") LocalDateTime now,
                                    @Param("limit") int limit, @Param("offset") long offset);

    @Select(STAFF_BATCHES + "AND b.voucher_id = #{batchId} " + STAFF_BATCH_GROUP)
    MealBatch findStaffBatch(@Param("shopId") long shopId, @Param("batchId") long batchId,
                             @Param("now") LocalDateTime now);

    @Data
    class DailyStats {
        private LocalDate date;
        private long batches;
        private long portions;
        private long awaitingPickup;
        private long redeemed;
        private long cancelled;
        private long expired;
        private long remaining;
    }

    @Select("""
            SELECT DATE(t.pickup_begin_time) AS date, COUNT(*) AS batches, SUM(t.initial_stock) AS portions,
                   SUM(t.awaiting_pickup) AS awaiting_pickup, SUM(t.redeemed) AS redeemed,
                   SUM(t.cancelled) AS cancelled, SUM(t.expired) AS expired, SUM(t.stock) AS remaining
            FROM (
            """ + STAFF_BATCHES + """
            AND b.pickup_begin_time >= #{from} AND b.pickup_begin_time < #{until}
            """ + STAFF_BATCH_GROUP + """
            ) t GROUP BY DATE(t.pickup_begin_time) ORDER BY date DESC
            """)
    List<DailyStats> listDailyStats(@Param("shopId") long shopId, @Param("now") LocalDateTime now,
                                   @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Select(BATCH_QUERY + """
            WHERE b.published = 0 AND b.booking_closed_at IS NULL AND v.status = 1 AND a.end_time > #{now}
            ORDER BY b.voucher_id LIMIT 50
            """)
    List<MealBatch> listUnpublishedBatches(LocalDateTime now);

    @Insert("""
            INSERT INTO tb_meal_batch(voucher_id, pickup_begin_time, pickup_end_time, initial_stock)
            VALUES (#{voucherId}, #{pickupBeginTime}, #{pickupEndTime}, #{initialStock})
            """)
    int insertBatch(@Param("voucherId") long voucherId,
                    @Param("pickupBeginTime") LocalDateTime pickupBeginTime,
                    @Param("pickupEndTime") LocalDateTime pickupEndTime, @Param("initialStock") int initialStock);

    @Update("UPDATE tb_meal_batch SET published = 1 WHERE voucher_id = #{voucherId} AND booking_closed_at IS NULL")
    int markPublished(long voucherId);

    @Update("""
            UPDATE tb_meal_batch SET booking_closed_at = #{now}
            WHERE voucher_id = #{batchId} AND booking_closed_at IS NULL
            """)
    int requestBookingClose(@Param("batchId") long batchId, @Param("now") LocalDateTime now);

    @Select(BATCH_QUERY + "WHERE b.close_applied = 0 AND b.booking_closed_at IS NOT NULL "
            + "ORDER BY b.booking_closed_at, b.voucher_id LIMIT 50")
    List<MealBatch> listPendingClosures();

    @Update("UPDATE tb_meal_batch SET close_applied = 1 WHERE voucher_id = #{batchId}")
    int markBookingClosed(long batchId);

    @Select("SELECT COUNT(*) FROM tb_shop_staff WHERE shop_id = #{shopId} AND user_id = #{userId}")
    int countStaff(@Param("shopId") long shopId, @Param("userId") long userId);

    @Select("""
            SELECT s.id, s.name FROM tb_shop_staff st JOIN tb_shop s ON s.id = st.shop_id
            WHERE st.user_id = #{userId} ORDER BY s.id
            """)
    List<Shop> listStaffShops(long userId);

    @Select("""
            SELECT u.id, u.nick_name FROM tb_shop_staff st JOIN tb_user u ON u.id = st.user_id
            WHERE st.shop_id = #{shopId} ORDER BY u.id
            """)
    List<User> listWindowStaff(long shopId);

    @Insert("""
            INSERT INTO tb_shop_staff (shop_id, user_id) VALUES (#{shopId}, #{userId})
            ON DUPLICATE KEY UPDATE user_id = #{userId}
            """)
    void bindWindowStaff(@Param("shopId") long shopId, @Param("userId") long userId);

    @Delete("DELETE FROM tb_shop_staff WHERE shop_id = #{shopId} AND user_id = #{userId}")
    void unbindWindowStaff(@Param("shopId") long shopId, @Param("userId") long userId);

    @Insert("INSERT INTO tb_meal_pickup(order_id, pickup_code) VALUES (#{orderId}, #{code})")
    int insertPickup(@Param("orderId") long orderId, @Param("code") String code);

    @Select(PICKUP_QUERY + "WHERE p.order_id = #{orderId}")
    MealPickup findPickup(long orderId);

    @Select(PICKUP_QUERY + "WHERE o.user_id = #{userId} ORDER BY o.id DESC LIMIT 30")
    List<MealPickup> listRecentPickups(long userId);

    @Select("""
            <script>
            """ + PICKUP_QUERY + """
            WHERE o.user_id = #{userId}
            <choose>
                <when test="status == 'AWAITING_PICKUP'">
                    AND o.status = 7 AND b.pickup_end_time &gt; #{now}
                </when>
                <when test="status == 'EXPIRED'">
                    AND (o.status = 8 OR (o.status = 7 AND b.pickup_end_time &lt;= #{now}))
                </when>
                <when test="status == 'REDEEMED'">AND o.status = 3</when>
                <when test="status == 'CANCELLED'">AND o.status = 4</when>
            </choose>
            ORDER BY
            <if test="status == 'AWAITING_PICKUP'">b.pickup_end_time,</if>
            o.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MealPickup> listPickups(@Param("userId") long userId, @Param("status") String status,
                                 @Param("now") LocalDateTime now, @Param("limit") int limit,
                                 @Param("offset") long offset);

    @Select("""
            SELECT o.id AS order_id, o.voucher_id, v.title, o.use_time AS redeemed_at, u.nick_name AS redeemer_name
            FROM tb_voucher_order o
            JOIN tb_meal_pickup p ON p.order_id = o.id
            JOIN tb_voucher v ON v.id = o.voucher_id
            LEFT JOIN tb_user u ON u.id = p.redeemed_by
            WHERE v.shop_id = #{shopId} AND o.voucher_id = #{batchId} AND o.status = 3
            ORDER BY o.use_time DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<MealPickup> listRedemptions(@Param("shopId") long shopId, @Param("batchId") long batchId,
                                     @Param("limit") int limit, @Param("offset") long offset);

    @Select("""
            SELECT o.id FROM tb_meal_batch b
            JOIN tb_voucher_order o ON o.voucher_id = b.voucher_id
            JOIN tb_meal_pickup p ON p.order_id = o.id
            WHERE b.pickup_end_time <= #{now} AND o.status = 7
            ORDER BY b.pickup_end_time, b.voucher_id, o.id LIMIT 200
            """)
    List<Long> listOverduePickups(LocalDateTime now);

    @Update("""
            <script>
            UPDATE tb_voucher_order SET status = 8 WHERE status = 7 AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int expirePickups(@Param("ids") List<Long> ids);

    @Update("""
            UPDATE tb_voucher_order SET status = 4
            WHERE id = #{orderId} AND user_id = #{userId} AND status = 7
            """)
    int cancel(@Param("orderId") long orderId, @Param("userId") long userId);

    @Update("UPDATE tb_voucher_order SET status = 3, use_time = #{now} WHERE id = #{orderId} AND status = 7")
    int redeem(@Param("orderId") long orderId, @Param("now") LocalDateTime now);

    @Update("UPDATE tb_meal_pickup SET redeemed_by = #{userId} WHERE order_id = #{orderId}")
    int recordRedeemer(@Param("orderId") long orderId, @Param("userId") long userId);

    @Update("UPDATE tb_seckill_voucher SET stock = stock + 1 WHERE voucher_id = #{voucherId}")
    int replenishDatabaseStock(long voucherId);

    @Insert("INSERT INTO tb_meal_restock(order_id, voucher_id) VALUES (#{orderId}, #{voucherId})")
    int enqueueRestock(@Param("orderId") long orderId, @Param("voucherId") long voucherId);

    @Select("""
            SELECT r.order_id AS id, r.voucher_id
            FROM tb_meal_restock r WHERE r.processed = 0 ORDER BY r.order_id LIMIT 50
            """)
    List<VoucherOrderDTO> listPendingRestocks();

    @Update("UPDATE tb_meal_restock SET processed = 1 WHERE order_id = #{orderId}")
    int markRestocked(long orderId);
}
