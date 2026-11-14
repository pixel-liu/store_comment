package com.foodiego.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MealPickup {
    public static final int AWAITING_PICKUP = 7;
    public static final int REDEEMED = 3;
    public static final int CANCELLED = 4;
    public static final int EXPIRED = 8;

    private Long orderId;
    private Long voucherId;
    private Long userId;
    private Long shopId;
    private String title;
    private String shopName;
    private String shopAddress;
    private String redeemerName;
    private LocalDateTime redeemedAt;
    private String pickupCode;
    private Integer status;
    private LocalDateTime bookingEndTime;
    private LocalDateTime pickupBeginTime;
    private LocalDateTime pickupEndTime;
}
