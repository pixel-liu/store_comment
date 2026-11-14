package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.foodiego.dto.Result;
import com.foodiego.dto.UserDTO;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.entity.Voucher;
import com.foodiego.entity.VoucherOrder;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.service.IVoucherService;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.SimpleLock;
import com.foodiego.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author FoodieGo Team
 * @since 2021-12-22
 */
//@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private IVoucherOrderService iVoucherOrderService;
    @Autowired
    private IVoucherService iVoucherService;

    // 秒杀券下单-lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 独立线程处理下单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    public void init() {
            SECKILL_ORDER_EXECUTOR.submit(new secKillVoucherOrderHandler());
    }

    // spring容器关闭前销毁secKillVoucherOrderHandler线程
    @PreDestroy
    public void destroy() {
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private class secKillVoucherOrderHandler implements Runnable {
        private String queueName = "stream.orders";
        private String consumerId = "c1";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", consumerId),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2L)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null ||list.isEmpty()) {
                        continue;
                    }
                    // 2.2.获取成功，创建订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    iVoucherOrderService.handleVoucherOrder(voucherOrder);
                    // 3.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    log.info("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList(){
            int maxRetry = 3;
            int retryCount = 0;
            while (true) {
                try {
                    // 1.处理pending-list队列订单信息 XREADGROUP GROUP g1 c1 COUNT 1   STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", consumerId),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.没有获取成功，结束
                        break;
                    }
                    // 2.2.获取成功，创建订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    iVoucherOrderService.handleVoucherOrder(voucherOrder);
                    // 3.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
                } catch (Exception e) {
                    log.info("处理 pending-list 异常");
                    retryCount++;
                    if (retryCount >= maxRetry) {
                        log.error("处理 pending-list 失败超过最大重试次数，放弃该消息: {}", e.getMessage());
                        break; // 退出循环，不要死等
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /**
     * 消息队列
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        // 1.1.获取用户id
        Long userId = UserHolder.getUser().getId();

        // 1.2.获取订单id
        long orderId = redisIdWorker.nextId("order");

        // 2.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result.intValue();

        // 3.结果不是0
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 4.结果是0，单开一个线程创建消息队列
        // 5.返回订单号
        return Result.ok(orderId);
    }
    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 扣减库存
        boolean updateSuccess = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) //乐观锁，更新数据时校验库存是否大于0
                .update();
        if (!updateSuccess) {
            // 库存不足，这是正常的业务失败，不需要重试！不需要抛异常！
            log.info("库存不足，订单创建失败: {}", voucherOrder.getId());
            return; // 正常结束，外层会执行 ACK
        }
        // 2. 插入订单 (确保数据库有 user_id + voucher_id 的唯一索引)
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            // 重复下单，也是正常的业务拦截，不需要重试！
            log.info("重复下单，订单创建失败: {}", voucherOrder.getId());
            return;
        }
    }

    @Override
    public void handleVoucherOrder(VoucherOrderDTO voucherOrderDTO) {

    }

    @Override
    public Result voucher(Long voucherId) {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断是否已下单
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .count();
        if (count > 0) {
            return Result.fail("不能重复下单");
        }
        // 3.查询优惠券信息
        Voucher voucher = iVoucherService.getById(voucherId);
        // 4.判断优惠券是否存在
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 5.判断优惠券类型
        if (voucher.getType() != 0) {
            return Result.fail("优惠券类型错误");
        }
        // 6.判断优惠券状态
        if (voucher.getStatus() != 1) {
            return Result.fail("优惠券状态错误");
        }
        // 7.生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 8.封装订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setStatus(1);
        voucherOrder.setPayType(1);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());
        // 9.插入订单
        save(voucherOrder);

        return Result.ok(orderId);
    }

    /**
     * 优惠券下单-一人一单
     *
     * @param voucherId
     * @return
     */
//    @Override
//    public Result secKillVoucher(Long voucherId) {
//        // 1.查询优惠券信息
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//        // 2.判断是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀活动尚未开始");
//        }
//        // 3.判断是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀活动已结束");
//        }
//        // 4.判断库存
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//        // synchronized锁 缺点：锁粒度太细，集群环境下锁不住
//        /*synchronized (userId.toString().intern()) { //intern（）会在字符串常量池检查有没有值一样，有这复用，不会创建新对象
//            //  拿到当前对象的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        }*/
//
//        // 分布式锁
//        // 1.自定义SimpleLock对象获取锁
//        /*SimpleLock lock = new SimpleLock("order:" + userId, stringRedisTemplate);
////        获取锁
//        boolean isLock = lock.tryLock(10L);
////        没拿到锁
//        if (!isLock) {
//            return Result.fail("请勿重复下单");
//        }
//        // 拿到锁
//        try {
//            //  拿到当前对象的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, userId);
//        } finally {
//            // 释放锁
//            lock.unlock();
//        }*/
//
//        // 2.Redisson 分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 判断锁是否成功
//        if (lock.tryLock()) {
//            try {
//                //  拿到当前对象的代理对象
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId, userId);
//            } finally {
//                lock.unlock();
//            }
//        }else {
//            return Result.fail("请勿重复下单");
//        }
//    }

    /**
     * 优惠券下单-乐观锁
     * @param voucherId
     * @return
     */
//    @Override
//    @Transactional
//    public Result secKillVoucher(Long voucherId) {
//        // 1.查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2.判断是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀活动尚未开始");
//        }
//        // 3.判断是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀活动已结束");
//        }
//        // 4.判断库存
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        // 5.扣减库存
//        boolean isUpdate = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock",0) //乐观锁，更新数据时校验库存是否大于0
//                .update();
//        if (!isUpdate) {
//            return Result.fail("库存不足");
//        }
//        // 6.创建订单
//        VoucherOrder order = new VoucherOrder();
//
//        // 6.1.生成订单号
//        long id = redisIdWorker.nextId("order");
//        order.setId(id);
//
//        // 6.2.代金券id
//        order.setVoucherId(voucherId);
//
//        // 6.3.用户id
//        UserDTO user = UserHolder.getUser();
//        order.setUserId(user.getId());
//
//        // 6.4.插入订单
//        iVoucherOrderService.save(order);
//
//        // 7.返回订单号
//        return Result.ok(id);
//    }
}
