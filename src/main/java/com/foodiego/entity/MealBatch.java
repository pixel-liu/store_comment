package com.foodiego.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MealBatch {
    public static final int VOUCHER_TYPE = 2;

    private Long voucherId;
    private Long shopId;
    private String shopName;
    private String shopAddress;
    private String title;
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private LocalDateTime pickupBeginTime;
    private LocalDateTime pickupEndTime;
    private boolean published;
    private LocalDateTime bookingClosedAt;
    private boolean closeApplied;
    private Integer initialStock;
    private long awaitingPickup;
    private long redeemed;
    private long cancelled;
    private long expired;
}
