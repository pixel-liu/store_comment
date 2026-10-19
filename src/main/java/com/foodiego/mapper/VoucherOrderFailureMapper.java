package com.foodiego.mapper;

import com.foodiego.dto.VoucherOrderDTO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** Terminal failure ledger prevents a late delivery from recreating a released order. */
public interface VoucherOrderFailureMapper {
    @Select("SELECT COUNT(*) FROM tb_voucher_order_failure WHERE order_id = #{orderId}")
    int countByOrderId(@Param("orderId") Long orderId);

    @Insert("INSERT INTO tb_voucher_order_failure(order_id, user_id, voucher_id) " +
            "VALUES(#{id}, #{userId}, #{voucherId})")
    int insert(VoucherOrderDTO order);
}
