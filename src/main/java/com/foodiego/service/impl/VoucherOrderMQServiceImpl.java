package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.dto.Result;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.entity.VoucherOrder;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.mapper.SeckillVoucherMapper;
import com.foodiego.mapper.VoucherOrderFailureMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.foodiego.config.RabbitMQConfig.SECKILL_ORDER_EXCHANGE;
import static com.foodiego.config.RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY;
import static com.foodiego.utils.RedisConstants.MQ_RETRY_ORDER_KEY;

@Service
@Slf4j
public class
VoucherOrderMQServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;
    @Resource
    private VoucherOrderFailureMapper voucherOrderFailureMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill2.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    

    // 优雅停机标记，防止 @Scheduled 在关闭期间继续访问 Redis
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // Instance-owned executor so closing one context never shuts down another instance.
    ThreadPoolExecutor asyncMqExecutor = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5000),
            r -> {
                    Thread t = new Thread(r, "async-mq-sender");
                    t.setDaemon(true);
                    return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);  // 先标记停机，阻止 @Scheduled 继续访问 Redis/MySQL
        log.info("开始关闭 MQ 异步发送线程池... 队列中待处理任务: {}",
                asyncMqExecutor.getQueue().size());
        asyncMqExecutor.shutdown();
        try {
            if (!asyncMqExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("线程池未在 10s 内完成，强制关闭，剩余任务: {}",
                        asyncMqExecutor.getQueue().size());
                asyncMqExecutor.shutdownNow();
            } else {
                log.info("MQ 异步发送线程池已优雅关闭");
            }
        } catch (InterruptedException e) {
            log.error("等待线程池关闭被中断", e);
            asyncMqExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    /**
     * 秒杀券下单
     *
     * 优化点：
     * 1. RedisIdWorker 批量预取ID，消除每次 Redis INCR 调用
     * 2. MQ 异步发送，消除 broker 确认的阻塞等待（~2ms）
     * 3. 异步失败时写入 Redis List 兜底，后续补偿
     * 4. Lua 使用 Redis 时钟校验活动时间，与扣库存保持原子性
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID无效");
        }
        Long userId = UserHolder.getUser().getId();
        // 1. 执行 Lua 脚本（Redis 原子校验 + 扣库存 + 标记用户）
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId,
                        RedisConstants.SECKILL_INFO_KEY + voucherId, "seckill:order:" + voucherId),
                voucherId.toString(), userId.toString());
        int r = result == null ? -1 : result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail("不能重复下单");
        }
        if (r == 3) {
            return Result.fail("秒杀活动不存在或已下线");
        }
        if (r == 4) {
            return Result.fail("秒杀尚未开始");
        }
        if (r == 5) {
            return Result.fail("秒杀已经结束");
        }
        if (r != 0) {
            return Result.fail("秒杀失败");
        }

        // 2. 生成订单ID（批量预取，无 Redis 调用）
        long orderId = redisIdWorker.nextId("order");

        // 3. 封装 DTO
        VoucherOrderDTO dto = new VoucherOrderDTO();
        dto.setId(orderId);
        dto.setVoucherId(voucherId);
        dto.setUserId(userId);

        // 4. 异步发送 MQ，不阻塞主线程
        asyncMqExecutor.submit(() -> {
            long sendStart = System.currentTimeMillis();
            try {
                rabbitTemplate.convertAndSend(SECKILL_ORDER_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, dto);
                long elapsed = System.currentTimeMillis() - sendStart;
                if (elapsed > 50) {
                    log.warn("MQ 发送耗时较长(非排队), orderId={}, elapsed={}ms", orderId, elapsed);
                }
            } catch (Exception e) {
                log.error("MQ 异步发送失败, orderId={}", orderId, e);
                // 写入 Redis 备份队列，定时任务补偿重发
                stringRedisTemplate.opsForList().leftPush(MQ_RETRY_ORDER_KEY,
                        orderId + ":" + voucherId + ":" + userId + ":0");
            }
        });

        // 5. 立即返回
        return Result.ok(orderId);
    }

    /**
     * 定时任务：补偿重发 Redis 备份队列中失败的 MQ 消息
     * 每 30 秒扫描一次，批量弹出并重试，最多重试 3 次
     */
    @Scheduled(fixedDelay = 30_000)
    public void retryMqFromRedis() {
        if (shuttingDown.get()) {
            return;
        }
        String redisKey = MQ_RETRY_ORDER_KEY;
        int batchSize = 100;
        for (int i = 0; i < batchSize; i++) {
            String entry;
            try {
                entry = stringRedisTemplate.opsForList().rightPop(redisKey);
            } catch (Exception e) {
                // 关闭期间 Redis 连接可能中断，静默退出
                if (shuttingDown.get()) {
                    log.info("应用关闭中，停止 Redis 补偿扫描");
                    return;
                }
                log.error("Redis rightPop 异常", e);
                break;
            }
            if (entry == null) {
                // 队列已空
                break;
            }
            // 格式: orderId:voucherId:userId:retryCount
            String[] parts = entry.split(":");
            if (parts.length != 4) {
                log.warn("Redis 备份队列数据格式异常，跳过: {}", entry);
                continue;
            }
            long orderId = Long.parseLong(parts[0]);
            long voucherId = Long.parseLong(parts[1]);
            long userId = Long.parseLong(parts[2]);
            int retryCount = Integer.parseInt(parts[3]);

            VoucherOrderDTO dto = new VoucherOrderDTO();
            dto.setId(orderId);
            dto.setVoucherId(voucherId);
            dto.setUserId(userId);

            try {
                rabbitTemplate.convertAndSend(SECKILL_ORDER_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, dto);
                log.info("补偿重发 MQ 成功, orderId={}, retryCount={}", orderId, retryCount);
            } catch (Exception e) {
                retryCount++;
                log.error("补偿重发 MQ 失败, orderId={}, retryCount={}", orderId, retryCount, e);
                if (retryCount < 3) {
                    // 未达上限，重新放回队列头部
                    stringRedisTemplate.opsForList().leftPush(redisKey,
                            orderId + ":" + voucherId + ":" + userId + ":" + retryCount);
                } else {
                    log.error("补偿重发 MQ 重试耗尽，消息丢弃, orderId={}, voucherId={}, userId={}",
                            orderId, voucherId, userId);
                }
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleVoucherOrder(VoucherOrderDTO voucherOrderDTO) {
        // Serialize deliveries across instances before inspecting committed orders.
        SeckillVoucher activity = seckillVoucherMapper.selectForUpdate(voucherOrderDTO.getVoucherId());
        if (activity == null) {
            throw new IllegalStateException("秒杀活动不存在");
        }
        if (voucherOrderFailureMapper.countByOrderId(voucherOrderDTO.getId()) > 0) {
            return;
        }
        VoucherOrder existing = getById(voucherOrderDTO.getId());
        if (existing != null) {
            if (!existing.getUserId().equals(voucherOrderDTO.getUserId()) ||
                    !existing.getVoucherId().equals(voucherOrderDTO.getVoucherId())) {
                throw new IllegalStateException("订单ID与业务信息不匹配");
            }
            return;
        }
        if (baseMapper.selectCount(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, voucherOrderDTO.getUserId())
                .eq(VoucherOrder::getVoucherId, voucherOrderDTO.getVoucherId())) > 0) {
            return;
        }
        boolean updateSuccess = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrderDTO.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!updateSuccess) {
            throw new IllegalStateException("库存不足，订单创建失败: " + voucherOrderDTO.getId());
        }
        // Insertion failures must roll back the stock decrement as well.
        VoucherOrder voucherOrder = BeanUtil.copyProperties(voucherOrderDTO, VoucherOrder.class);
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单保存失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markOrderFailed(VoucherOrderDTO order) {
        // Shares the same lock as normal processing: failure and success cannot race.
        if (seckillVoucherMapper.selectForUpdate(order.getVoucherId()) == null) {
            throw new IllegalStateException("秒杀活动不存在");
        }
        if (getById(order.getId()) != null || baseMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, order.getUserId())
                        .eq(VoucherOrder::getVoucherId, order.getVoucherId())) > 0) {
            return false;
        }
        if (voucherOrderFailureMapper.countByOrderId(order.getId()) == 0) {
            voucherOrderFailureMapper.insert(order);
        }
        return true;
    }

    @Override
    public Result voucher(Long voucherId) {
        return null;
    }

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
    }
}
