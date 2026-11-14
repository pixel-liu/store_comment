package com.foodiego.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 优惠券订单实体
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher_order")
@ApiModel(description = "优惠券订单（含秒杀订单）")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.INPUT)
    @ApiModelProperty(value = "订单主键ID（全局唯一ID生成）", example = "1234567890123456789")
    private Long id;

    /**
     * 下单的用户id
     */
    @ApiModelProperty(value = "下单用户ID", example = "1001")
    private Long userId;

    /**
     * 购买的代金券id
     */
    @ApiModelProperty(value = "购买的优惠券ID", example = "1")
    private Long voucherId;

    /**
     * 支付方式 1：余额支付；2：支付宝；3：微信
     */
    @ApiModelProperty(value = "支付方式（1：余额支付，2：支付宝，3：微信）", example = "1")
    private Integer payType;

    /**
     * 订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款；7：待取餐
     */
    @ApiModelProperty(value = "订单状态（1：未支付，2：已支付，3：已核销，4：已取消，5：退款中，6：已退款，7：待取餐）", example = "1")
    private Integer status;

    /**
     * 下单时间
     */
    @ApiModelProperty(value = "下单时间", hidden = true)
    private LocalDateTime createTime;

    /**
     * 支付时间
     */
    @ApiModelProperty(value = "支付时间", hidden = true)
    private LocalDateTime payTime;

    /**
     * 核销时间
     */
    @ApiModelProperty(value = "核销时间", hidden = true)
    private LocalDateTime useTime;

    /**
     * 退款时间
     */
    @ApiModelProperty(value = "退款时间", hidden = true)
    private LocalDateTime refundTime;

    /**
     * 更新时间
     */
    @ApiModelProperty(value = "更新时间", hidden = true)
    private LocalDateTime updateTime;


}
