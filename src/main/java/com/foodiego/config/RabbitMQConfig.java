package com.foodiego.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class RabbitMQConfig {

    /**
     * 秒杀订单队列
     */
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";

    /**
     * 秒杀订单交换机
     */
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";

    /**
     * 秒杀订单路由键
     */
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    // ────────────────────── 死信队列 ──────────────────────

    /** 死信交换机 */
    public static final String SECKILL_DLX_EXCHANGE = "seckill.order.dlx.exchange";

    /** 死信队列 */
    public static final String SECKILL_DLX_QUEUE = "seckill.order.dlx.queue";

    /** 死信路由键 */
    public static final String SECKILL_DLX_ROUTING_KEY = "seckill.order.dlx";

    /**
     * 声明秒杀订单队列（绑定死信交换机）
     * 重试耗尽后 basicNack(requeue=false) → 自动路由到死信队列
     * 注意：RabbitMQ 不允许修改已存在队列的参数，如需 TTL 请先手动删除旧队列
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .deadLetterExchange(SECKILL_DLX_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_DLX_ROUTING_KEY)
                .build();
    }

    /**
     * 声明死信队列 — 重试耗尽 / TTL 超时的消息最终落点
     */
    @Bean
    public Queue seckillDlxQueue() {
        return QueueBuilder.durable(SECKILL_DLX_QUEUE).build();
    }

    /**
     * 声明交换机
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return ExchangeBuilder.directExchange(SECKILL_ORDER_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 声明死信交换机
     */
    @Bean
    public DirectExchange seckillDlxExchange() {
        return ExchangeBuilder.directExchange(SECKILL_DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 绑定队列和交换机
     */
    @Bean
    public Binding binding(Queue seckillOrderQueue, DirectExchange seckillOrderExchange) {
        return BindingBuilder.bind(seckillOrderQueue)
                .to(seckillOrderExchange)
                .with(SECKILL_ORDER_ROUTING_KEY);
    }

    /**
     * 绑定死信队列和死信交换机
     */
    @Bean
    public Binding dlxBinding(Queue seckillDlxQueue, DirectExchange seckillDlxExchange) {
        return BindingBuilder.bind(seckillDlxQueue)
                .to(seckillDlxExchange)
                .with(SECKILL_DLX_ROUTING_KEY);
    }

    /**
     * 配置消息转换器
     * 将Java对象转换为JSON字符串，再发送到RabbitMQ
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 注册 Publisher Confirm 回调
     * 感知消息是否到达 broker；未确认的记录保留在 Redis outbox 中等待重试。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());

        // 发布确认回调：消息到达交换机回调
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ 消息未到达交换机, correlationData={}, cause={}", correlationData, cause);
            }
        });

        // 退回回调：消息未路由到队列时回调 (Spring AMQP 2.2.x API)
        template.setMandatory(true);
        template.setReturnCallback((message, replyCode, replyText, exchange, routingKey) -> {
            log.error("MQ 消息未路由到队列, exchange={}, routingKey={}, replyCode={}, replyText={}",
                    exchange, routingKey, replyCode, replyText);
        });

        return template;
    }
}
