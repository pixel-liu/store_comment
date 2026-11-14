package com.foodiego.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(description = "秒杀订单消息体（投递到MQ）")
public class VoucherOrderDTO implements Serializable {

    private final static long serialVersionUID = 1L;

    @ApiModelProperty(value = "订单ID")
    private Long id;

    @ApiModelProperty(value = "优惠券ID", example = "1")
    private Long voucherId;

    @ApiModelProperty(value = "用户ID", example = "1001")
    private Long userId;

    private boolean mealReservation;
}
