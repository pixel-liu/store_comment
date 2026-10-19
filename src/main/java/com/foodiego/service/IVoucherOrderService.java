package com.foodiego.service;

import com.foodiego.dto.Result;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券下单
     * @param voucherId
     * @return
     */
    Result secKillVoucher(Long voucherId);

    void handleVoucherOrder(VoucherOrder voucherOrder);

    void handleVoucherOrder(VoucherOrderDTO voucherOrderDTO);

    default boolean markOrderFailed(VoucherOrderDTO order) {
        throw new UnsupportedOperationException("Failure handling is not supported by this implementation");
    }

    Result voucher(Long voucherId);
}
