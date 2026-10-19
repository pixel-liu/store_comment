package com.foodiego.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.dto.Result;
import com.foodiego.entity.Voucher;
import com.foodiego.mapper.VoucherMapper;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.service.IVoucherService;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.RedisIdWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplates;

    @Value("${foodiego.seckill.zone-id:Asia/Shanghai}")
    private String zoneId = "Asia/Shanghai";

    private static final DefaultRedisScript<Long> INIT_SCRIPT = new DefaultRedisScript<>();
    static {
        INIT_SCRIPT.setLocation(new ClassPathResource("init-seckill.lua"));
        INIT_SCRIPT.setResultType(Long.class);
    }


    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        ZoneId zone = ZoneId.of(zoneId);
        if (voucher.getStock() == null || voucher.getStock() <= 0 ||
                voucher.getBeginTime() == null || voucher.getEndTime() == null ||
                !voucher.getEndTime().isAfter(voucher.getBeginTime()) ||
                !voucher.getEndTime().isAfter(LocalDateTime.now(zone))) {
            throw new IllegalArgumentException("库存必须大于0，且秒杀结束时间必须晚于开始时间和当前时间");
        }
        // 保存优惠券
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }

        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀活动保存失败");
        }

        long begin = voucher.getBeginTime().atZone(zone).toInstant().toEpochMilli();
        long end = voucher.getEndTime().atZone(zone).toInstant().toEpochMilli();
        // Never expose an activity whose database transaction may still roll back.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplates.execute(INIT_SCRIPT,
                        Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                                RedisConstants.SECKILL_INFO_KEY + voucher.getId()),
                        voucher.getStock().toString(), Long.toString(begin), Long.toString(end),
                        Long.toString(end + TimeUnit.DAYS.toMillis(1)));
            }
        });
    }


}
