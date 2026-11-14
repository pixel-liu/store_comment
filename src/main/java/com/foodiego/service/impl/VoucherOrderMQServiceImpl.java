package com.foodiego.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.foodiego.dto.Result;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.entity.MealBatch;
import com.foodiego.entity.MealPickup;
import com.foodiego.entity.SeckillVoucher;
import com.foodiego.entity.VoucherOrder;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.mapper.SeckillVoucherMapper;
import com.foodiego.mapper.VoucherOrderFailureMapper;
import com.foodiego.mapper.MealMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.foodiego.service.ISeckillVoucherService;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.service.IVoucherService;
import com.foodiego.utils.RedisConstants;
import com.foodiego.utils.RedisIdWorker;
import com.foodiego.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.foodiego.config.RabbitMQConfig.SECKILL_ORDER_EXCHANGE;
import static com.foodiego.config.RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY;
import static com.foodiego.utils.RedisConstants.MQ_OUTBOX_ORDER_KEY;
import static com.foodiego.utils.RedisConstants.MQ_OUTBOX_PROCESSING_KEY;

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
    @Resource
    private IVoucherService voucherService;
    @Resource
    private MealMapper mealMapper;
    @Value("${foodiego.seckill.zone-id:Asia/Shanghai}")
    private String zoneId = "Asia/Shanghai";

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final SecureRandom PICKUP_CODE_RANDOM = new SecureRandom();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill2.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    

    // 优雅停机标记，防止 @Scheduled 在关闭期间继续访问 Redis
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @PreDestroy
    public void shutdown() {
        // Stop scheduled outbox reads. Entries stay in Redis and are picked up after restart.
        shuttingDown.set(true);
    }


    /**
     * 秒杀券下单
     *
     * 优化点：
     * 1. RedisIdWorker 批量预取ID，消除每次 Redis INCR 调用
     * 2. 在同一 Lua 脚本中写入 Redis durable outbox
     * 3. outbox 经 publisher confirm 后才移除，进程崩溃后可重试
     * 4. Lua 使用 Redis 时钟校验活动时间，与扣库存保持原子性
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        return reserve(voucherId, false, null);
    }

    @Override
    public Result reserveMeal(Long batchId) {
        return reserve(batchId, true, null);
    }

    @Override
    public Result confirmWaitlist(Long batchId, String attempt) {
        if (!MealWaitlistService.validAttempt(attempt)) return Result.fail("候补编号无效");
        return reserve(batchId, true, attempt);
    }

    private Result reserve(Long voucherId, boolean meal, String attempt) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        if (voucherId == null || voucherId <= 0) {
            return Result.fail(meal ? "批次ID无效" : "优惠券ID无效");
        }
        Long userId = UserHolder.getUser().getId();
        // Generate the ID before Lua so the reservation and durable outbox event are atomic.
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行 Lua 脚本（Redis 原子校验 + 扣库存 + 标记用户 + 写 outbox）
        List<String> keys = new ArrayList<>(List.of(RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_INFO_KEY + voucherId, "seckill:order:" + voucherId,
                MQ_OUTBOX_ORDER_KEY));
        if (meal) {
            keys.add(RedisConstants.MEAL_RECEIPT_KEY + orderId);
            keys.addAll(MealWaitlistService.inventoryKeys(voucherId));
        }
        List<String> args = new ArrayList<>(List.of(voucherId.toString(), userId.toString(), Long.toString(orderId)));
        if (attempt != null) args.add(attempt);
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, keys, args.toArray());
        int r = result == null ? -1 : result.intValue();
        if (r == 1) {
            return Result.fail("库存不足");
        }
        if (r == 2) {
            return Result.fail(meal ? "本批次已预约过，每个账号限一份" : "不能重复下单");
        }
        if (r == 3) {
            return Result.fail(meal ? "余量餐批次尚未发布或已下线" : "秒杀活动不存在或已下线");
        }
        if (r == 4) {
            return Result.fail(meal ? "预约尚未开始" : "秒杀尚未开始");
        }
        if (r == 5) {
            return Result.fail(meal ? "预约已经截止" : "秒杀已经结束");
        }
        if (r == 6) {
            return Result.fail(meal ? "余量餐批次不存在" : "请从余量餐页面预约");
        }
        if (r == 7) return Result.fail("余量优先分配给候补，请刷新后查看");
        if (r == 8) {
            String record = (String) stringRedisTemplate.opsForHash().get(
                    RedisConstants.waitlistRecordKey(voucherId), userId.toString());
            String confirmed = attempt + ":CONFIRMED:";
            String failed = attempt + ":FAILED:";
            if (record != null && record.startsWith(confirmed)) return mealReceipt(record.substring(confirmed.length()));
            if (record != null && record.startsWith(failed)) return mealReceipt(record.substring(failed.length()));
            return Result.fail("候补记录已失效，请查看我的预约");
        }
        if (r == 9) return Result.fail("候补名额已失效或尚未轮到，请刷新候补");
        if (r != 0) {
            return Result.fail(meal ? "预约失败，请刷新余量后再试" : "秒杀失败");
        }

        // 2. The scheduled publisher drains the durable outbox. It removes an entry only after a broker confirm.
        return meal ? mealReceipt(Long.toString(orderId)) : Result.ok(orderId);
    }

    private Result mealReceipt(String orderId) {
        return Result.ok(Map.of("id", orderId, "status", "PROCESSING"));
    }

    /**
     * Durable outbox publisher. Entries are first moved to a processing list;
     * acknowledged entries are removed only after a positive publisher confirm.
     */
    @Scheduled(fixedDelayString = "${foodiego.seckill.outbox-poll-ms:1000}")
    public void retryMqFromRedis() {
        if (shuttingDown.get()) {
            return;
        }
        int batchSize = 100;
        // Retry anything a previous worker moved before crashing.
        List<String> processingEntries = stringRedisTemplate.opsForList()
                .range(MQ_OUTBOX_PROCESSING_KEY, 0, batchSize - 1);
        if (processingEntries != null) {
            for (String entry : processingEntries) {
                publishOutboxEntry(entry);
            }
        }
        // Atomically move new events to the processing list before attempting delivery.
        for (int i = 0; i < batchSize; i++) {
            String entry = stringRedisTemplate.opsForList().rightPopAndLeftPush(
                    MQ_OUTBOX_ORDER_KEY, MQ_OUTBOX_PROCESSING_KEY);
            if (entry == null) {
                return;
            }
            publishOutboxEntry(entry);
        }
    }

    private void publishOutboxEntry(String entry) {
        try {
            String[] parts = entry.split(":");
            if (parts.length != 3 && (parts.length != 4 || !"meal".equals(parts[3]))) {
                throw new IllegalArgumentException("outbox 消息格式错误: " + entry);
            }
            VoucherOrderDTO dto = new VoucherOrderDTO();
            dto.setId(Long.parseLong(parts[0]));
            dto.setVoucherId(Long.parseLong(parts[1]));
            dto.setUserId(Long.parseLong(parts[2]));
            dto.setMealReservation(parts.length == 4);
            CorrelationData correlationData = new CorrelationData(parts[0]);
            rabbitTemplate.convertAndSend(SECKILL_ORDER_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, dto, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck() || correlationData.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ 未确认消息: " + parts[0]);
            }
            stringRedisTemplate.opsForList().remove(MQ_OUTBOX_PROCESSING_KEY, 1, entry);
        } catch (Exception e) {
            // Preserve the processing entry for the next scheduled attempt; do not discard reservations.
            log.error("outbox 发布失败，将保留并重试，entry={}", entry, e);
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
        if (voucherOrderDTO.isMealReservation()) {
            MealBatch batch = mealMapper.findBatch(voucherOrderDTO.getVoucherId());
            if (batch == null || !LocalDateTime.now(ZoneId.of(zoneId)).isBefore(batch.getPickupEndTime())) {
                throw new IllegalStateException("取餐时段已结束或批次不存在");
            }
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
        if (voucherOrderDTO.isMealReservation()) {
            voucherOrder.setStatus(MealPickup.AWAITING_PICKUP);
        }
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单保存失败");
        }
        if (voucherOrderDTO.isMealReservation()) {
            String code = String.format(Locale.ROOT, "%08d", PICKUP_CODE_RANDOM.nextInt(100_000_000));
            if (mealMapper.insertPickup(voucherOrder.getId(), code) != 1) {
                throw new IllegalStateException("取餐码保存失败");
            }
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
    @Transactional(rollbackFor = Exception.class)
    public Result voucher(Long voucherId) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID无效");
        }
        com.foodiego.entity.Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (!Integer.valueOf(0).equals(voucher.getType())) {
            return Result.fail("请通过秒杀入口购买该优惠券");
        }
        if (!Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("优惠券当前不可购买");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(UserHolder.getUser().getId());
        order.setVoucherId(voucherId);
        order.setPayType(1);
        order.setStatus(1);
        try {
            if (!save(order)) {
                throw new IllegalStateException("订单创建失败");
            }
        } catch (DuplicateKeyException e) {
            return Result.fail("不能重复下单");
        }
        return Result.ok(order.getId());
    }

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
    }
}
