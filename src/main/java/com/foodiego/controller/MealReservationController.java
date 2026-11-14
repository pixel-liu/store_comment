package com.foodiego.controller;

import com.foodiego.dto.Result;
import com.foodiego.mapper.MealMapper;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.service.impl.MealReservationService;
import com.foodiego.service.impl.MealWaitlistService;
import com.foodiego.utils.UserHolder;
import io.swagger.annotations.Api;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Map;

@Api(tags = "余量餐预约与核销")
@RestController
@RequiredArgsConstructor
public class MealReservationController {
    private final MealReservationService meals;
    private final IVoucherOrderService orders;
    private final MealMapper mealMapper;
    private final MealWaitlistService waitlist;

    public record RedeemRequest(String pickupCode) {}
    public record WaitlistRequest(String attempt) {}

    @GetMapping("/meal-batches")
    public Result listBatches(@RequestParam(required = false) Long windowId,
                              @RequestParam(name = "q", required = false) String query,
                              @RequestParam(required = false) String status,
                              @RequestParam(defaultValue = "false") boolean today) {
        return meals.listBatches(windowId, query, status, today);
    }

    @GetMapping("/campus-windows")
    public Result listTodayWindows() {
        return Result.ok(meals.listTodayWindows());
    }

    @GetMapping("/campus-windows/{windowId}")
    public Result windowDetail(@PathVariable long windowId) {
        return meals.windowDetail(windowId);
    }

    @GetMapping("/staff/shops")
    public Result listStaffShops() {
        return Result.ok(mealMapper.listStaffShops(UserHolder.getUser().getId()).stream()
                .map(shop -> Map.of("id", shop.getId().toString(), "name", shop.getName())).toList());
    }

    @PostMapping("/staff/shops/{shopId}/meal-batches")
    public Result publish(@PathVariable long shopId, @RequestBody MealReservationService.PublishBatch request) {
        return meals.publish(shopId, UserHolder.getUser().getId(), request);
    }

    @GetMapping("/staff/shops/{shopId}/meal-batches")
    public Result listStaffBatches(@PathVariable long shopId, @RequestParam(required = false) Integer page) {
        return meals.listStaffBatches(shopId, UserHolder.getUser().getId(), page);
    }

    @GetMapping("/staff/shops/{shopId}/meal-batches/{batchId}")
    public Result batchDetail(@PathVariable long shopId, @PathVariable long batchId) {
        return meals.batchDetail(shopId, batchId, UserHolder.getUser().getId());
    }

    @PostMapping("/staff/shops/{shopId}/meal-batches/{batchId}/close-booking")
    public Result closeBooking(@PathVariable long shopId, @PathVariable long batchId) {
        return meals.closeBooking(shopId, batchId, UserHolder.getUser().getId());
    }

    @GetMapping("/staff/shops/{shopId}/redemptions")
    public Result listRedemptions(@PathVariable long shopId, @RequestParam long batchId,
                                  @RequestParam(defaultValue = "1") int page) {
        return meals.redemptions(shopId, batchId, UserHolder.getUser().getId(), page);
    }

    @GetMapping("/staff/shops/{shopId}/meal-stats")
    public Result mealStats(@PathVariable long shopId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return meals.mealStats(shopId, UserHolder.getUser().getId(), from, to);
    }

    @PostMapping("/meal-batches/{batchId}/reservations")
    public Result reserve(@PathVariable long batchId) {
        return orders.reserveMeal(batchId);
    }

    @PostMapping("/meal-batches/{batchId}/waitlist")
    public Result joinWaitlist(@PathVariable long batchId) {
        return waitlist.join(batchId, UserHolder.getUser().getId());
    }

    @GetMapping("/meal-waitlist")
    public Result myWaitlist() {
        return Result.ok(waitlist.list(UserHolder.getUser().getId()));
    }

    @PostMapping("/meal-waitlist/{batchId}/confirm")
    public Result confirmWaitlist(@PathVariable long batchId, @RequestBody WaitlistRequest request) {
        return orders.confirmWaitlist(batchId, request.attempt());
    }

    @PostMapping("/meal-waitlist/{batchId}/leave")
    public Result leaveWaitlist(@PathVariable long batchId, @RequestBody WaitlistRequest request) {
        return waitlist.leave(batchId, UserHolder.getUser().getId(), request.attempt());
    }

    @GetMapping("/meal-reservations")
    public Result listReservations(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer page) {
        long userId = UserHolder.getUser().getId();
        if (status == null && page == null) return Result.ok(meals.listRecentReservations(userId));
        return meals.listReservations(userId, status == null ? "ALL" : status, page == null ? 1 : page);
    }

    @GetMapping("/meal-reservations/{id}")
    public Result findReservation(@PathVariable long id) {
        return meals.findReservation(id, UserHolder.getUser().getId());
    }

    @PostMapping("/meal-reservations/{id}/cancel")
    public Result cancel(@PathVariable long id) {
        return meals.cancel(id, UserHolder.getUser().getId());
    }

    @PostMapping("/staff/shops/{shopId}/meal-reservations/{id}/redeem")
    public Result redeem(@PathVariable long shopId, @PathVariable long id, @RequestBody RedeemRequest request) {
        return meals.redeem(id, shopId, UserHolder.getUser().getId(), request.pickupCode());
    }
}
