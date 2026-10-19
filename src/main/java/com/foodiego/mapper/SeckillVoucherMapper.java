package com.foodiego.mapper;

import com.foodiego.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2022-01-04
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {
    @Select("SELECT * FROM tb_seckill_voucher WHERE voucher_id = #{voucherId} FOR UPDATE")
    SeckillVoucher selectForUpdate(@Param("voucherId") Long voucherId);
}
