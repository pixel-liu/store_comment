package com.foodiego.handler;

import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;

import java.io.IOException;
import java.util.Arrays;

import static com.foodiego.config.RabbitMQConfig.SECKILL_DLX_QUEUE;
import static com.foodiego.config.RabbitMQConfig.SECKILL_ORDER_QUEUE;

@Component
@Slf4j
@RequiredArgsConstructor
public class RabbitMQHandler {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RetryTemplate orderRetry = createRetryTemplate();
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>();
    static {
        COMPENSATE_SCRIPT.setLocation(new ClassPathResource("compensate-seckill.lua"));
        COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    private static RetryTemplate createRetryTemplate() {
        RetryTemplate retry = new RetryTemplate();
        retry.setRetryPolicy(new SimpleRetryPolicy(4));
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(100);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(1000);
        retry.setBackOffPolicy(backoff);
        return retry;
    }

    // ──────────────────── 正常秒杀队列消费 ────────────────────

    @RabbitListener(queues = SECKILL_ORDER_QUEUE)
    public void handleVoucherOrder(Message msg, Channel channel, VoucherOrderDTO voucherOrderDTO) throws IOException {
        long deliveryTag = msg.getMessageProperties().getDeliveryTag();
        try {
            orderRetry.execute(context -> {
                voucherOrderService.handleVoucherOrder(voucherOrderDTO);
                return null;
            });
        } catch (Exception e) {
            log.error("订单处理异常 orderId={}", voucherOrderDTO.getId(), e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        // ACK failures must result in redelivery, never compensation of a committed order.
        channel.basicAck(deliveryTag, false);
    }

    // ──────────────────── 死信队列消费：补偿回收 ────────────────────

    /**
     * Commit a failure tombstone before idempotent Redis compensation.
     * An existing successful order must never be compensated.
     */
    @RabbitListener(queues = SECKILL_DLX_QUEUE)
    public void handleDeadLetter(Message msg, Channel channel, VoucherOrderDTO voucherOrderDTO) throws IOException {
        long deliveryTag = msg.getMessageProperties().getDeliveryTag();
        long voucherId = voucherOrderDTO.getVoucherId();
        long userId = voucherOrderDTO.getUserId();
        long orderId = voucherOrderDTO.getId();

        log.warn("死信队列收到消息, orderId={}, voucherId={}, userId={}", orderId, voucherId, userId);

        try {
            orderRetry.execute(context -> {
                if (voucherOrderService.markOrderFailed(voucherOrderDTO)) {
                    Long compensated = stringRedisTemplate.execute(COMPENSATE_SCRIPT,
                            Arrays.asList("seckill:stock:" + voucherId, "seckill:order:" + voucherId,
                                    "seckill:compensated:" + orderId, "seckill:dead:orders"),
                            String.valueOf(userId), orderId + ":" + voucherId + ":" + userId);
                    if (compensated == null) {
                        throw new IllegalStateException("Redis compensation returned no result");
                    }
                }
                return null;
            });
            log.info("死信处理完成, orderId={}", orderId);

        } catch (Exception e) {
            log.error("死信补偿失败, orderId={}", orderId, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        channel.basicAck(deliveryTag, false);
    }
}
