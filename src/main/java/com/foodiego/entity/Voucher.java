package com.foodiego.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * 优惠券实体
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher")
@ApiModel(description = "优惠券信息（含普通券和秒杀券）")
public class Voucher implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty(value = "优惠券主键ID", example = "1")
    private Long id;

    /**
     * 商铺id
     */
    @ApiModelProperty(value = "所属商铺ID", example = "1")
    private Long shopId;

    /**
     *
     * 代金券标题
     */
    @ApiModelProperty(value = "优惠券标题", example = "100元代金券")
    private String title;

    /**
     * 副标题
     */
    @ApiModelProperty(value = "副标题", example = "满200可用")
    private String subTitle;

    /**
     * 使用规则
     */
    @ApiModelProperty(value = "使用规则说明")
    private String rules;

    /**
     * 支付金额，单位：分
     */
    @ApiModelProperty(value = "支付金额（分）", example = "8000")
    private Long payValue;

    /**
     * 抵扣金额，单位：分
     */
    @ApiModelProperty(value = "抵扣金额（分）", example = "10000")
    private Long actualValue;

    /**
     * 优惠券类型 0：普通券；1：秒杀券；2：余量餐批次
     */
    @ApiModelProperty(value = "优惠券类型（0：普通券，1：秒杀券，2：余量餐批次）", example = "1")
    private Integer type;

    /**
     * 优惠券状态 1：上架；2：下架；3：过期
     */
    @ApiModelProperty(value = "优惠券状态（1：上架，2：下架，3：过期）", example = "1")
    private Integer status;

    /**
     * 库存
     */
    @TableField(exist = false)
    @ApiModelProperty(value = "库存数量（非数据库字段，仅秒杀券使用）", example = "100")
    private Integer stock;

    /**
     * 生效时间
     */
    @TableField(exist = false)
    @ApiModelProperty(value = "秒杀开始时间（非数据库字段，仅秒杀券使用）")
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    @TableField(exist = false)
    @ApiModelProperty(value = "秒杀结束时间（非数据库字段，仅秒杀券使用）")
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    @ApiModelProperty(value = "创建时间", hidden = true)
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    @ApiModelProperty(value = "更新时间", hidden = true)
    private LocalDateTime updateTime;


}
