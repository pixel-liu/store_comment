package com.foodiego.controller;

import org.springframework.context.annotation.Profile;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.foodiego.dto.Result;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.service.IVoucherService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  秒杀订单接口
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
@Api(tags = "秒杀订单管理")
@RestController
@Profile("legacy")
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Autowired
    private IVoucherOrderService iVoucherOrderService;
    @Autowired
    private StringRedisTemplate redisTemplate;


    /**
     * 秒杀接口限流降级处理
     */
    public Result seckillBlockHandler(Long voucherId, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        return Result.fail("系统繁忙，请稍后再试");
    }

    /**
     * 秒杀券下单
     * @param voucherId
     * @return
     */
    @ApiOperation("秒杀券下单 - 高并发抢购接口")
    @PostMapping("seckill/{id}")
    @SentinelResource(value = "seckillVoucher", blockHandler = "seckillBlockHandler")
    public Result seckillVoucher(@ApiParam(value = "秒杀券ID", required = true) @PathVariable("id") Long voucherId) throws InterruptedException {
        return iVoucherOrderService.secKillVoucher(voucherId);
    }

    /**
     * 优惠券下单
     * @param voucherId
     * @return
     */
    @ApiOperation("普通优惠券下单")
    @PostMapping("/{voucherId}")
    public Result voucher(@ApiParam(value = "优惠券ID", required = true) @PathVariable("voucherId") Long voucherId) {
        return iVoucherOrderService.voucher(voucherId);
    }

    @ApiOperation("Redis连接测试")
    @GetMapping("/ping-redis")
    public String ping() {
        long start = System.nanoTime();
        redisTemplate.opsForValue().increment("ping", 1);
        long cost = (System.nanoTime() - start) / 1_000_000;
        return "cost: " + cost + " ms";
    }
}
