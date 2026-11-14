package com.foodiego.service.impl;

import com.foodiego.dto.Result;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.MealBatch;
import com.foodiego.entity.MealPickup;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.entity.Shop;
import com.foodiego.entity.Voucher;
import com.foodiego.mapper.MealMapper;
import com.foodiego.mapper.SeckillVoucherMapper;
import com.foodiego.mapper.VoucherOrderFailureMapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.foodiego.utils.RedisConstants.MEAL_RECEIPT_KEY;
import static com.foodiego.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
@RequiredArgsConstructor
@Slf4j
public class MealReservationService {
    private static final String UNAVAILABLE_RESERVATION = "找不到此预约";
    private static final String WINDOW_FORBIDDEN = "你没有该窗口的查看权限";
    private static final int PAGE_SIZE = 15;
    private static final int MENU_LIMIT = 50;
    private static final Set<String> RESERVATION_FILTERS = Set.of("ALL", "AWAITING_PICKUP", "REDEEMED", "CANCELLED", "EXPIRED");
    private static final Set<String> MENU_FILTERS = Set.of("OPEN", "SCHEDULED", "SOLD_OUT");

    private final MealMapper mealMapper;
    private final SeckillVoucherMapper activityMapper;
    private final VoucherOrderFailureMapper failureMapper;
    private final IVoucherService voucherService;
    private final ISeckillVoucherService activityService;
    private final StringRedisTemplate redis;
    private final MealInventoryTasks inventoryTasks;

    @Value("${foodiego.seckill.zone-id:Asia/Shanghai}")
    private String zoneId;

    public record PublishBatch(String title, Integer stock, LocalDateTime beginTime, LocalDateTime endTime,
                               LocalDateTime pickupBeginTime, LocalDateTime pickupEndTime) {}

    public record BatchView(String id, String shopName, String title, int available, boolean bookingOpen,
                            LocalDateTime beginTime, LocalDateTime endTime,
                            LocalDateTime pickupBeginTime, LocalDateTime pickupEndTime, boolean pickupToday,
                            String windowId, String status) {}

    public record WindowView(String id, String name, String address, List<BatchView> batches) {}
    public record WindowOption(String id, String name) {}

    public record StaffBatchView(String id, String title, String status, int remaining, long awaitingPickup,
                                 long redeemed, LocalDateTime pickupBeginTime, LocalDateTime pickupEndTime) {}

    public record ReservationView(String id, String status, String title, String shopName,
                                  LocalDateTime pickupBeginTime, LocalDateTime pickupEndTime,
                                  String pickupCode, boolean canCancel, String shopAddress) {}

    public record Page<T>(List<T> items, boolean hasNext) {}
    public record ReservationPage(List<ReservationView> items, boolean hasNext, ReservationView nextPickup) {}

    public record BatchDetail(String id, String title, String status, int portions, int remaining,
                              long awaitingPickup, long redeemed, long cancelled, long expired,
                              LocalDateTime beginTime, LocalDateTime endTime, LocalDateTime pickupBeginTime,
                              LocalDateTime pickupEndTime, LocalDateTime bookingClosedAt, boolean canClose) {}

    public record RedemptionView(String orderId, String title, LocalDateTime redeemedAt, String staffName) {}

    @Transactional
    public Result publish(long shopId, long staffId, PublishBatch request) {
        if (mealMapper.countStaff(shopId, staffId) == 0) {
            return Result.fail("你没有该窗口的发布权限");
        }
        String title = request.title() == null ? "" : request.title().strip();
        if (title.isEmpty() || title.length() > 80 || request.stock() == null || request.stock() < 1) {
            return Result.fail("请输入 1 至 80 字的餐品名称和正整数份数");
        }
        LocalDateTime begin = request.beginTime();
        LocalDateTime end = request.endTime();
        LocalDateTime pickupBegin = request.pickupBeginTime();
        LocalDateTime pickupEnd = request.pickupEndTime();
        if (begin == null || end == null || pickupBegin == null || pickupEnd == null ||
                !end.isAfter(begin) || !end.isAfter(now()) || pickupBegin.isBefore(begin) ||
                !pickupEnd.isAfter(pickupBegin) || pickupEnd.isBefore(end)) {
            return Result.fail("请检查预约和取餐时段，取餐截止不能早于预约截止");
        }

        Voucher voucher = new Voucher().setShopId(shopId).setTitle(title)
                .setType(MealBatch.VOUCHER_TYPE).setStatus(1).setPayValue(0L).setActualValue(0L);
        if (!voucherService.save(voucher) || !activityService.save(new SeckillVoucher()
                .setVoucherId(voucher.getId()).setStock(request.stock()).setBeginTime(begin).setEndTime(end)) ||
                mealMapper.insertBatch(voucher.getId(), pickupBegin, pickupEnd, request.stock()) != 1) {
            throw new IllegalStateException("余量餐批次保存失败");
        }
        return Result.ok(Map.of("id", voucher.getId().toString()));
    }

    public Result listBatches(Long windowId, String query, String status, boolean todayOnly) {
        if (windowId != null && windowId <= 0) return Result.fail("窗口编号无效");
        if (status != null && !MENU_FILTERS.contains(status)) return Result.fail("不支持的餐品状态");
        String title = query == null ? "" : query.strip();
        if (title.length() > 80) return Result.fail("菜名搜索最多 80 字");
        String pattern = title.isEmpty() ? null : "%" + title.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return Result.ok(readMenu(windowId, pattern, status, todayOnly, now()));
    }

    public Result windowDetail(long windowId) {
        Shop shop = mealMapper.findCampusWindow(windowId);
        if (shop == null) return Result.fail("找不到此取餐窗口");
        return Result.ok(new WindowView(shop.getId().toString(), shop.getName(), shop.getAddress(),
                readMenu(windowId, null, null, false, now())));
    }

    public List<WindowOption> listTodayWindows() {
        LocalDateTime now = now();
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        return mealMapper.listTodayWindows(now, today, today.plusDays(1)).stream()
                .map(shop -> new WindowOption(shop.getId().toString(), shop.getName())).toList();
    }

    private List<BatchView> readMenu(Long windowId, String pattern, String status, boolean todayOnly, LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1);
        List<BatchView> views = new ArrayList<>();
        // Scan more candidates when a live Redis stock filter removes some SQL matches.
        for (long offset = 0; views.size() < MENU_LIMIT; offset += MENU_LIMIT) {
            List<MealBatch> batches = mealMapper.listAvailableBatches(now, windowId, pattern, status,
                    todayOnly ? today : null, tomorrow, MENU_LIMIT, offset);
            List<String> quantities = redisStock(batches);
            for (int index = 0; index < batches.size() && views.size() < MENU_LIMIT; index++) {
                MealBatch batch = batches.get(index);
                int available = stockAt(quantities, index);
                boolean bookingOpen = !now.isBefore(batch.getBeginTime());
                String batchStatus = !bookingOpen ? "SCHEDULED" : available > 0 ? "OPEN" : "SOLD_OUT";
                if (status != null && !status.equals(batchStatus)) continue;
                boolean pickupToday = batch.getPickupBeginTime().isBefore(tomorrow) && batch.getPickupEndTime().isAfter(today);
                views.add(new BatchView(batch.getVoucherId().toString(), batch.getShopName(), batch.getTitle(), available,
                        bookingOpen, batch.getBeginTime(), batch.getEndTime(), batch.getPickupBeginTime(), batch.getPickupEndTime(),
                        pickupToday, batch.getShopId().toString(), batchStatus));
            }
            if (batches.size() < MENU_LIMIT) break;
        }
        return views;
    }

    public Result listStaffBatches(long shopId, long staffId, Integer page) {
        if (mealMapper.countStaff(shopId, staffId) == 0) {
            return Result.fail(WINDOW_FORBIDDEN);
        }
        if (page != null && page < 1) return Result.fail("页码必须大于等于 1");
        LocalDateTime now = now();
        List<MealBatch> batches = mealMapper.listStaffBatches(shopId, now, page == null ? 30 : PAGE_SIZE + 1,
                page == null ? 0 : offset(page));
        List<String> quantities = redisStock(batches);
        List<StaffBatchView> views = new ArrayList<>(batches.size());
        for (int index = 0; index < batches.size(); index++) {
            MealBatch batch = batches.get(index);
            boolean liveStock = batch.isPublished() && batch.getBookingClosedAt() == null && now.isBefore(batch.getEndTime());
            views.add(new StaffBatchView(batch.getVoucherId().toString(), batch.getTitle(), batchStatus(batch, now),
                    liveStock ? stockAt(quantities, index) : batch.getStock(), batch.getAwaitingPickup(), batch.getRedeemed(),
                    batch.getPickupBeginTime(), batch.getPickupEndTime()));
        }
        return Result.ok(page == null ? views : paginate(views));
    }

    public Result batchDetail(long shopId, long batchId, long staffId) {
        if (mealMapper.countStaff(shopId, staffId) == 0) return Result.fail(WINDOW_FORBIDDEN);
        LocalDateTime now = now();
        MealBatch batch = mealMapper.findStaffBatch(shopId, batchId, now);
        if (batch == null) return Result.fail("找不到此批次");
        return Result.ok(new BatchDetail(batch.getVoucherId().toString(), batch.getTitle(), batchStatus(batch, now),
                batch.getInitialStock(), batch.getStock(), batch.getAwaitingPickup(), batch.getRedeemed(),
                batch.getCancelled(), batch.getExpired(), batch.getBeginTime(), batch.getEndTime(),
                batch.getPickupBeginTime(), batch.getPickupEndTime(), batch.getBookingClosedAt(),
                batch.getBookingClosedAt() == null && now.isBefore(batch.getEndTime())));
    }

    public Result closeBooking(long shopId, long batchId, long staffId) {
        if (mealMapper.countStaff(shopId, staffId) == 0) return Result.fail("你没有该窗口的关单权限");
        LocalDateTime now = now();
        MealBatch batch = mealMapper.findStaffBatch(shopId, batchId, now);
        if (batch == null) return Result.fail("找不到此批次");
        if (batch.isCloseApplied() || !now.isBefore(batch.getEndTime())) {
            return Result.ok(Map.of("status", "BOOKING_CLOSED"));
        }
        // Persist the intent before touching Redis so the scheduler can finish an interrupted close.
        mealMapper.requestBookingClose(batchId, now);
        try {
            inventoryTasks.applyBookingClose(batch);
            return Result.ok(Map.of("status", "BOOKING_CLOSED"));
        } catch (RuntimeException e) {
            log.warn("余量餐批次 {} 关单待重试", batchId, e);
            return Result.ok(Map.of("status", "CLOSING"));
        }
    }

    public Result redemptions(long shopId, long batchId, long staffId, int page) {
        if (mealMapper.countStaff(shopId, staffId) == 0) return Result.fail(WINDOW_FORBIDDEN);
        if (page < 1) return Result.fail("页码必须大于等于 1");
        List<RedemptionView> records = mealMapper.listRedemptions(shopId, batchId, PAGE_SIZE + 1, offset(page)).stream()
                .map(pickup -> new RedemptionView(pickup.getOrderId().toString(), pickup.getTitle(),
                        pickup.getRedeemedAt(), pickup.getRedeemerName())).toList();
        return Result.ok(paginate(records));
    }

    public Result mealStats(long shopId, long staffId, LocalDate from, LocalDate to) {
        if (mealMapper.countStaff(shopId, staffId) == 0) return Result.fail(WINDOW_FORBIDDEN);
        LocalDateTime now = now();
        if (to == null) to = now.toLocalDate();
        if (to.getYear() < 1000 || to.getYear() > 9998) return Result.fail("请选择有效日期");
        if (from == null) from = to.minusDays(6);
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= 31 || from.getYear() < 1000) {
            return Result.fail("请选择不超过 31 天的日期范围");
        }
        return Result.ok(mealMapper.listDailyStats(shopId, now, from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    }

    private long offset(int page) {
        return (long) (page - 1) * PAGE_SIZE;
    }

    private <T> Page<T> paginate(List<T> items) {
        boolean hasNext = items.size() > PAGE_SIZE;
        return new Page<>(hasNext ? items.subList(0, PAGE_SIZE) : items, hasNext);
    }

    private List<String> redisStock(List<MealBatch> batches) {
        if (batches.isEmpty()) {
            return List.of();
        }
        List<String> keys = batches.stream().map(batch -> SECKILL_STOCK_KEY + batch.getVoucherId()).toList();
        return redis.opsForValue().multiGet(keys);
    }

    private int stockAt(List<String> quantities, int index) {
        String value = quantities == null ? null : quantities.get(index);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private String batchStatus(MealBatch batch, LocalDateTime now) {
        if (!now.isBefore(batch.getPickupEndTime())) return "CLOSED";
        if (!now.isBefore(batch.getEndTime())) return "BOOKING_CLOSED";
        if (batch.getBookingClosedAt() != null) return batch.isCloseApplied() ? "BOOKING_CLOSED" : "CLOSING";
        if (!batch.isPublished()) return "PUBLISHING";
        return now.isBefore(batch.getBeginTime()) ? "SCHEDULED" : "OPEN";
    }

    public List<ReservationView> listRecentReservations(long userId) {
        LocalDateTime now = now();
        return mealMapper.listRecentPickups(userId).stream().map(pickup -> reservationView(pickup, now)).toList();
    }

    public Result listReservations(long userId, String status, int page) {
        if (!RESERVATION_FILTERS.contains(status)) return Result.fail("不支持的预约状态");
        if (page < 1) return Result.fail("页码必须大于等于 1");
        LocalDateTime now = now();
        Page<ReservationView> results = paginate(mealMapper.listPickups(userId, status, now, PAGE_SIZE + 1, offset(page))
                .stream().map(pickup -> reservationView(pickup, now)).toList());
        ReservationView next = mealMapper.listPickups(userId, "AWAITING_PICKUP", now, 1, 0).stream()
                .findFirst().map(pickup -> reservationView(pickup, now)).orElse(null);
        return Result.ok(new ReservationPage(results.items(), results.hasNext(), next));
    }

    public Result findReservation(long orderId, long userId) {
        MealPickup pickup = mealMapper.findPickup(orderId);
        if (pickup != null) {
            return pickup.getUserId() == userId ? Result.ok(reservationView(pickup, now())) : Result.fail(UNAVAILABLE_RESERVATION);
        }
        VoucherOrderDTO failure = failureMapper.findMealFailure(orderId, userId);
        if (failure != null) {
            return Result.ok(reservationView(orderId, failure.getVoucherId(), "FAILED"));
        }
        String receipt = redis.opsForValue().get(MEAL_RECEIPT_KEY + orderId);
        String userPrefix = userId + ":";
        if (receipt == null || !receipt.startsWith(userPrefix)) {
            return Result.fail(UNAVAILABLE_RESERVATION);
        }
        long batchId = Long.parseLong(receipt.substring(userPrefix.length()));
        return Result.ok(reservationView(orderId, batchId, "PROCESSING"));
    }

    @Transactional
    public Result cancel(long orderId, long userId) {
        MealPickup pickup = mealMapper.findPickup(orderId);
        if (pickup == null || pickup.getUserId() != userId) {
            return Result.fail(UNAVAILABLE_RESERVATION);
        }
        // Acquire locks in the same order as the asynchronous order consumer.
        SeckillVoucher activity = activityMapper.selectForUpdate(pickup.getVoucherId());
        if (activity == null || !now().isBefore(activity.getEndTime())) {
            return Result.fail("已过预约截止时间");
        }
        if (mealMapper.cancel(orderId, userId) != 1) {
            return Result.fail("预约状态已变化，请刷新");
        }
        if (mealMapper.replenishDatabaseStock(pickup.getVoucherId()) != 1 ||
                mealMapper.enqueueRestock(orderId, pickup.getVoucherId()) != 1) {
            throw new IllegalStateException("取消预约时库存回补任务保存失败");
        }
        return Result.ok();
    }

    @Transactional
    public Result redeem(long orderId, long shopId, long staffId, String code) {
        if (mealMapper.countStaff(shopId, staffId) == 0) {
            return Result.fail("你没有该窗口的核销权限");
        }
        MealPickup pickup = mealMapper.findPickup(orderId);
        if (pickup == null || pickup.getShopId() != shopId || !pickup.getPickupCode().equals(code)) {
            return Result.fail("预约号或取餐码有误");
        }
        LocalDateTime now = now();
        if (now.isBefore(pickup.getPickupBeginTime()) || !now.isBefore(pickup.getPickupEndTime())) {
            return Result.fail("当前不在取餐时段");
        }
        if (mealMapper.redeem(orderId, now) != 1) {
            return Result.fail("预约状态已变化，请刷新");
        }
        if (mealMapper.recordRedeemer(orderId, staffId) != 1) {
            throw new IllegalStateException("核销人保存失败");
        }
        return Result.ok();
    }

    private ReservationView reservationView(MealPickup pickup, LocalDateTime now) {
        String status = switch (pickup.getStatus()) {
            case MealPickup.REDEEMED -> "REDEEMED";
            case MealPickup.CANCELLED -> "CANCELLED";
            case MealPickup.EXPIRED -> "EXPIRED";
            case MealPickup.AWAITING_PICKUP -> now.isBefore(pickup.getPickupEndTime()) ? "AWAITING_PICKUP" : "EXPIRED";
            default -> throw new IllegalStateException("未知预约状态");
        };
        boolean awaitingPickup = "AWAITING_PICKUP".equals(status);
        return new ReservationView(pickup.getOrderId().toString(), status, pickup.getTitle(), pickup.getShopName(),
                pickup.getPickupBeginTime(), pickup.getPickupEndTime(), awaitingPickup ? pickup.getPickupCode() : null,
                awaitingPickup && now.isBefore(pickup.getBookingEndTime()), pickup.getShopAddress());
    }

    private ReservationView reservationView(long orderId, long batchId, String status) {
        MealBatch batch = mealMapper.findBatch(batchId);
        if ("PROCESSING".equals(status) && !now().isBefore(batch.getPickupEndTime())) {
            status = "EXPIRED";
        }
        return new ReservationView(Long.toString(orderId), status, batch.getTitle(), batch.getShopName(),
                batch.getPickupBeginTime(), batch.getPickupEndTime(), null, false, batch.getShopAddress());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(zoneId));
    }
}
